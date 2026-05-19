// src/main/java/org/saturn/reports/TripxReportProvider.java
// OsrmTripClient + OsrmTableClient 2026 WrA (wra.eng@gmail.com)

package org.saturn.reports;

import org.saturn.helper.model.DeviceUtil;
import org.saturn.model.Device;
import org.saturn.model.Geofence;
import org.saturn.model.Position;
import org.saturn.reports.common.ReportUtils;
import org.saturn.routing.OsrmTableClient;
import org.saturn.routing.OsrmTableClient.DestinationEta;
import org.saturn.routing.OsrmTableClient.TableResult;
import org.saturn.routing.OsrmTripClient;
import org.saturn.routing.OsrmTripClient.TripResult;
import org.saturn.routing.OsrmTripClient.WaypointOrder;
import org.saturn.session.cache.CacheManager;
import org.saturn.storage.Storage;
import org.saturn.storage.StorageException;
import org.saturn.storage.query.Columns;
import org.saturn.storage.query.Condition;
import org.saturn.storage.query.Request;
import org.saturn.utils.GeofenceUtils;
import org.saturn.utils.GeofenceUtils.Coordinate;

import jakarta.annotation.Nullable;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * TripxReportProvider — dual-mode report provider.
 *
 * MODE 1: TRIP  → /trip/v1
 *   Input  : last known position per device (from deviceIds)
 *   Process: OsrmTripClient solves TSP — optimal visit order
 *   Output : TripPlanResult — ordered stops with ETA per leg
 *
 * MODE 2: TABLE → /table/v1
 *   Input  : last known position per device (sources) +
 *            geofence centroids (destinations, via GeofenceUtils)
 *   Process: OsrmTableClient computes duration/distance matrix
 *   Output : TableMatrixResult — ETA matrix + nearest vehicle per geofence
 *
 * Endpoints:
 *   GET /reports/tripx-snap   → getObjectsTrip()   (JSON, MODE 1)
 *   GET /reports/table       → getObjectsTable()  (JSON, MODE 2)
 */
public class TripxReportProvider {

    private static final Logger LOGGER = Logger.getLogger(TripxReportProvider.class.getName());

    private final ReportUtils    reportUtils;
    private final Storage        storage;
    private final CacheManager   cacheManager;

    @Nullable
    private final OsrmTripClient  osrmTripClient;

    @Nullable
    private final OsrmTableClient osrmTableClient;

    // -------------------------------------------------------------------------
    // Result types
    // -------------------------------------------------------------------------

    /**
     * One stop in the optimized trip plan.
     */
    public static class TripStop {
        /** Original input device position */
        public final Position position;
        /** Device resolved from position.getDeviceId() */
        public final String   deviceName;
        /** OSRM visit order index within the trip (0-based) */
        public final int      visitOrder;
        /** Leg distance from previous stop (meters). 0 for the first stop. */
        public final double   legDistanceMeters;
        /** Leg duration from previous stop (seconds). 0 for the first stop. */
        public final double   legDurationSeconds;

        public TripStop(Position position, String deviceName, int visitOrder,
                        double legDistanceMeters, double legDurationSeconds) {
            this.position          = position;
            this.deviceName        = deviceName;
            this.visitOrder        = visitOrder;
            this.legDistanceMeters = legDistanceMeters;
            this.legDurationSeconds = legDurationSeconds;
        }
    }

    /**
     * Full trip plan result (MODE 1 — TRIP).
     */
    public static class TripPlanResult {
        /** Stops in optimized visit order */
        public final List<TripStop> stops;
        public final double         totalDistanceMeters;
        public final double         totalDurationSeconds;
        /** Raw OSRM TripResult — includes geometry for map rendering */
        public final TripResult     osrmResult;

        public TripPlanResult(List<TripStop> stops, double totalDistanceMeters,
                              double totalDurationSeconds, TripResult osrmResult) {
            this.stops                = Collections.unmodifiableList(stops);
            this.totalDistanceMeters  = totalDistanceMeters;
            this.totalDurationSeconds = totalDurationSeconds;
            this.osrmResult           = osrmResult;
        }
    }

    /**
     * One cell in the ETA matrix (MODE 2 — TABLE).
     */
    public static class EtaCell {
        public final String deviceName;
        public final String geofenceName;
        public final double durationSeconds;
        public final double distanceMeters;
        /** true if this is the nearest vehicle for this geofence */
        public final boolean nearest;

        public EtaCell(String deviceName, String geofenceName,
                       double durationSeconds, double distanceMeters, boolean nearest) {
            this.deviceName      = deviceName;
            this.geofenceName    = geofenceName;
            this.durationSeconds = durationSeconds;
            this.distanceMeters  = distanceMeters;
            this.nearest         = nearest;
        }
    }

    /**
     * Full table matrix result (MODE 2 — TABLE).
     * cells : flat list of EtaCell, row-major (device × geofence)
     * nearestPerGeofence : geofenceName → nearest EtaCell
     */
    public static class TableMatrixResult {
        public final List<EtaCell>         cells;
        public final Map<String, EtaCell>  nearestPerGeofence;
        public final List<String>          deviceNames;
        public final List<String>          geofenceNames;
        /** Raw OSRM TableResult — durations[][] and distances[][] */
        public final TableResult           osrmResult;

        public TableMatrixResult(List<EtaCell> cells, Map<String, EtaCell> nearestPerGeofence,
                                 List<String> deviceNames, List<String> geofenceNames,
                                 TableResult osrmResult) {
            this.cells              = Collections.unmodifiableList(cells);
            this.nearestPerGeofence = Collections.unmodifiableMap(nearestPerGeofence);
            this.deviceNames        = Collections.unmodifiableList(deviceNames);
            this.geofenceNames      = Collections.unmodifiableList(geofenceNames);
            this.osrmResult         = osrmResult;
        }
    }

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    @Inject
    public TripxReportProvider(
            ReportUtils reportUtils,
            Storage storage,
            CacheManager cacheManager,
            @Nullable OsrmTripClient osrmTripClient,
            @Nullable OsrmTableClient osrmTableClient) {
        this.reportUtils      = reportUtils;
        this.storage          = storage;
        this.cacheManager     = cacheManager;
        this.osrmTripClient   = osrmTripClient;
        this.osrmTableClient  = osrmTableClient;

        if (osrmTripClient != null) {
            LOGGER.info("TripxReportProvider: OsrmTripClient (trip/v1) ready");
        }
        if (osrmTableClient != null) {
            LOGGER.info("TripxReportProvider: OsrmTableClient (table/v1) ready");
        }
        if (osrmTripClient == null && osrmTableClient == null) {
            LOGGER.warning("TripxReportProvider: no routing clients available — "
                    + "set routing.trip.enabled=true or routing.table.enabled=true");
        }
    }

    // -------------------------------------------------------------------------
    // MODE 1 — TRIP
    // -------------------------------------------------------------------------

    /**
     * Plan an optimized multi-stop trip from the last known position of each device.
     *
     * Called by: GET /reports/tripx-snap
     *
     * @param userId    requesting user
     * @param deviceIds device IDs whose last position will be used as stops
     * @param groupIds  group IDs (expanded to devices internally)
     * @return TripPlanResult, or null if OsrmTripClient is not enabled
     * @throws StorageException on DB error
     */
    public TripPlanResult getObjectsTrip(
            long userId,
            Collection<Long> deviceIds,
            Collection<Long> groupIds) throws StorageException {

        if (osrmTripClient == null) {
            LOGGER.warning("TripxReportProvider.getObjectsTrip: OsrmTripClient not enabled "
                    + "(routing.trip.enabled=false or routing.type != osrm)");
            return null;
        }

        // 1. Resolve devices and collect last positions
        List<Device>   devices       = new ArrayList<>(
                DeviceUtil.getAccessibleDevices(storage, userId, deviceIds, groupIds));
        List<Position> lastPositions = new ArrayList<>();
        List<String>   deviceNames  = new ArrayList<>();

        for (Device device : devices) {
            Position last = getLastPosition(device.getId());
            if (last != null) {
                lastPositions.add(last);
                deviceNames.add(device.getName());
            } else {
                LOGGER.fine("TripxReportProvider: no last position for device "
                        + device.getName() + " (id=" + device.getId() + ") — skipped");
            }
        }

        if (lastPositions.size() < 2) {
            LOGGER.warning("TripxReportProvider.getObjectsTrip: need at least 2 devices with last position");
            return null;
        }

        // 2. Call OSRM trip/v1
        TripResult tripResult = osrmTripClient.planTrip(lastPositions);
        if (tripResult == null) {
            LOGGER.warning("TripxReportProvider.getObjectsTrip: OSRM returned no trip result");
            return null;
        }

        // 3. Build ordered TripStop list
        //    waypoints[i].waypointIndex = visit order for input position i
        List<TripStop> stops = new ArrayList<>();

        // Build a lookup: waypointIndex → input position index
        // (OSRM returns waypoints in INPUT order, each with waypointIndex = visit order)
        int[] visitOrders = new int[lastPositions.size()];
        for (WaypointOrder wp : tripResult.waypoints) {
            if (wp.original != null) {
                int inputIdx = lastPositions.indexOf(wp.original);
                if (inputIdx >= 0) {
                    visitOrders[inputIdx] = wp.waypointIndex;
                }
            }
        }

        for (int i = 0; i < lastPositions.size(); i++) {
            int    visitOrder = visitOrders[i];
            double legDist    = 0.0;
            double legDur     = 0.0;

            // Match leg by visit order (leg[k] = from stop k to stop k+1)
            if (visitOrder > 0 && visitOrder - 1 < tripResult.legs.size()) {
                var leg = tripResult.legs.get(visitOrder - 1);
                legDist = leg.distanceMeters;
                legDur  = leg.durationSeconds;
            }

            stops.add(new TripStop(
                    lastPositions.get(i),
                    deviceNames.get(i),
                    visitOrder,
                    legDist,
                    legDur));
        }

        // Sort stops by visit order for display
        stops.sort((a, b) -> Integer.compare(a.visitOrder, b.visitOrder));

        LOGGER.info(String.format(
                "TripxReportProvider: trip planned — %d stops, %.2f km, %.0f s",
                stops.size(),
                tripResult.totalDistanceMeters / 1000.0,
                tripResult.totalDurationSeconds));

        return new TripPlanResult(
                stops,
                tripResult.totalDistanceMeters,
                tripResult.totalDurationSeconds,
                tripResult);
    }

    // -------------------------------------------------------------------------
    // MODE 2 — TABLE
    // -------------------------------------------------------------------------

    /**
     * Compute ETA matrix: all active vehicles → all geofence centroids.
     *
     * Called by: GET /reports/table
     *
     * @param userId     requesting user
     * @param deviceIds  device IDs whose last position will be used as sources
     * @param groupIds   group IDs (expanded to devices internally)
     * @param geofenceIds geofence IDs to use as destinations (centroids via GeofenceUtils)
     * @return TableMatrixResult, or null if OsrmTableClient is not enabled
     * @throws StorageException on DB error
     */
    public TableMatrixResult getObjectsTable(
            long userId,
            Collection<Long> deviceIds,
            Collection<Long> groupIds,
            Collection<Long> geofenceIds) throws StorageException {

        if (osrmTableClient == null) {
            LOGGER.warning("TripxReportProvider.getObjectsTable: OsrmTableClient not enabled "
                    + "(routing.table.enabled=false or routing.type != osrm)");
            return null;
        }

        // 1. Resolve devices → source coordinates
        List<Device>     devices     = new ArrayList<>(
                DeviceUtil.getAccessibleDevices(storage, userId, deviceIds, groupIds));
        List<Coordinate> sources     = new ArrayList<>();
        List<String>     deviceNames = new ArrayList<>();

        for (Device device : devices) {
            Position last = getLastPosition(device.getId());
            if (last != null) {
                sources.add(new Coordinate(last.getLatitude(), last.getLongitude()));
                deviceNames.add(device.getName());
            } else {
                LOGGER.fine("TripxReportProvider: no last position for device "
                        + device.getName() + " — skipped from table sources");
            }
        }

        if (sources.isEmpty()) {
            LOGGER.warning("TripxReportProvider.getObjectsTable: no source coordinates available");
            return null;
        }

        // 2. Resolve geofences → destination coordinates (centroid of each POLYGON)
        List<Coordinate> destinations  = new ArrayList<>();
        List<String>     geofenceNames = new ArrayList<>();

        for (long geofenceId : geofenceIds) {
            Geofence geofence = storage.getObject(Geofence.class, new Request(
                    new Columns.All(), new Condition.Equals("id", geofenceId)));
            if (geofence == null) {
                LOGGER.fine("TripxReportProvider: geofence id=" + geofenceId + " not found — skipped");
                continue;
            }

            String area = geofence.getArea();
            if (!GeofenceUtils.isPolygon(area)) {
                LOGGER.warning("TripxReportProvider: geofence '" + geofence.getName()
                        + "' area is not a POLYGON — skipped");
                continue;
            }

            Coordinate centroid = GeofenceUtils.centroid(area);
            if (centroid == null) {
                LOGGER.warning("TripxReportProvider: failed to compute centroid for geofence '"
                        + geofence.getName() + "' — skipped");
                continue;
            }

            destinations.add(centroid);
            geofenceNames.add(geofence.getName());
        }

        if (destinations.isEmpty()) {
            LOGGER.warning("TripxReportProvider.getObjectsTable: no valid geofence destinations");
            return null;
        }

        // 3. Call OSRM table/v1
        TableResult tableResult = osrmTableClient.computeMatrix(sources, destinations);
        if (tableResult == null) {
            LOGGER.warning("TripxReportProvider.getObjectsTable: OSRM returned no table result");
            return null;
        }

        // 4. Build flat EtaCell list + nearestPerGeofence map
        //    Also find nearest device per geofence
        List<DestinationEta> nearestList = osrmTableClient.findNearest(sources, destinations);

        // Build lookup: destinationIndex → nearest sourceIndex
        Map<Integer, Integer> nearestSrcByDst = new LinkedHashMap<>();
        for (DestinationEta eta : nearestList) {
            nearestSrcByDst.putIfAbsent(eta.destinationIndex, eta.sourceIndex);
        }

        List<EtaCell>         cells              = new ArrayList<>();
        Map<String, EtaCell>  nearestPerGeofence = new LinkedHashMap<>();

        for (int d = 0; d < tableResult.destinationCount(); d++) {
            String geofenceName = geofenceNames.get(d);
            int    nearestSrc   = nearestSrcByDst.getOrDefault(d, -1);

            for (int s = 0; s < tableResult.sourceCount(); s++) {
                double dur  = tableResult.durations[s][d];
                double dist = tableResult.distances[s][d];

                // Sentinel → represent as -1 in the result (no route)
                if (dur >= Double.MAX_VALUE) {
                    dur  = -1.0;
                    dist = -1.0;
                }

                boolean isNearest = (s == nearestSrc);
                EtaCell cell = new EtaCell(
                        deviceNames.get(s),
                        geofenceName,
                        dur,
                        dist,
                        isNearest);

                cells.add(cell);
                if (isNearest) {
                    nearestPerGeofence.put(geofenceName, cell);
                }
            }
        }

        LOGGER.info(String.format(
                "TripxReportProvider: table computed — %d devices × %d geofences (%d cells)",
                tableResult.sourceCount(), tableResult.destinationCount(), cells.size()));

        return new TableMatrixResult(
                cells,
                nearestPerGeofence,
                deviceNames,
                geofenceNames,
                tableResult);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Get the last known position for a device from CacheManager (in-memory).
     * Zero DB query — CacheManager.getPosition() returns peekLast() from the
     * device's position deque, which is updated in real-time as positions arrive.
     * Returns null if the device has no cached position yet.
     */
    //private Position getLastPosition(long deviceId) {
    //    return cacheManager.getPosition(deviceId);
    //}

    private Position getLastPosition(long deviceId) throws StorageException {
    // 1. cache first (zero DB query, realtime)
    Position cached = cacheManager.getPosition(deviceId);
    if (cached != null) {
        return cached;
    }

    // 2. Fallback to DB — get last device.positionId
    // Pattern CacheManager.addDevice()
    Device device = storage.getObject(Device.class, new Request(
            new Columns.All(), new Condition.Equals("id", deviceId)));
    if (device == null || device.getPositionId() <= 0) {
        return null;
    }
    return storage.getObject(Position.class, new Request(
            new Columns.All(), new Condition.Equals("id", device.getPositionId())));
            }
}
