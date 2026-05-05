// src/main/java/org/saturn/reports/RouteReportProvider.java
// OsrmClient 2026 WrA (wra.eng@gmail.com)

package org.saturn.reports;

import org.apache.poi.ss.util.WorkbookUtil;
import org.saturn.config.Config;
import org.saturn.config.Keys;
import org.saturn.helper.model.DeviceUtil;
import org.saturn.helper.model.PositionUtil;
import org.saturn.model.Device;
import org.saturn.model.Group;
import org.saturn.model.Position;
import org.saturn.reports.common.ReportUtils;
import org.saturn.reports.model.DeviceReportSection;
import org.saturn.routing.OsrmClient;
import org.saturn.routing.OsrmMatchClient;
import org.saturn.storage.Storage;
import org.saturn.storage.StorageException;
import org.saturn.storage.query.Columns;
import org.saturn.storage.query.Condition;
import org.saturn.storage.query.Request;

import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

public class RouteReportProvider {

    private static final Logger LOGGER = Logger.getLogger(RouteReportProvider.class.getName());

    private final Config config;
    private final ReportUtils reportUtils;
    private final Storage storage;

    // [Patch-1] OsrmClient has been added as a field;
    // it can be null if routing is disabled
    private final OsrmClient osrmClient;

    // OsrmMatchClient — optional, injected if routing.match.enabled=true
    // Takes priority over OsrmClient when available
    private final OsrmMatchClient osrmMatchClient;

    private final Map<String, Integer> namesCount = new HashMap<>();

    // [Patch-2] Logging
    @Inject
    public RouteReportProvider(Config config, ReportUtils reportUtils, Storage storage,
            @Nullable OsrmClient osrmClient,
            @Nullable OsrmMatchClient osrmMatchClient) {
        this.config = config;
        this.reportUtils = reportUtils;
        this.storage = storage;
        this.osrmClient = osrmClient;
        this.osrmMatchClient = osrmMatchClient;
        if (osrmMatchClient != null) {
            LOGGER.info("RouteReportProvider: using OsrmMatchClient (match/v1) as primary router");
        } else if (osrmClient != null) {
            LOGGER.info("RouteReportProvider: using OsrmClient (route/v1) as router");
        }
    }

    /**
     * Route positions through OSRM — prefer match/v1 if available, fallback to route/v1.
     */
    private List<Position> routePositions(List<Position> rawPositions) {
        if (osrmMatchClient != null && rawPositions.size() >= 2) {
            return osrmMatchClient.matchRoute(rawPositions);
        } else if (osrmClient != null && rawPositions.size() >= 2) {
            return osrmClient.calculateRoute(rawPositions);
        }
        return rawPositions;
    }

    public Collection<Position> getObjects(long userId, Collection<Long> deviceIds, Collection<Long> groupIds,
            Date from, Date to) throws StorageException {
        reportUtils.checkPeriodLimit(from, to);

        // [Patch-3] getObjects() does NOT apply routing — this method is used by the JSON API
        // and must return the raw location data stored in the database
        ArrayList<Position> result = new ArrayList<>();
        for (Device device : DeviceUtil.getAccessibleDevices(storage, userId, deviceIds, groupIds)) {
            result.addAll(PositionUtil.getPositions(storage, device.getId(), from, to));
        }
        return result;
    }

    /**
     * Route report with OSRM snap — JSON response for snap pages UI.
     * Uses match/v1 (if enabled) or route/v1.
     * Called by the ReportResource endpoint /reports/route-snap.
     */
    public Collection<Position> getObjectsSnapped(long userId, Collection<Long> deviceIds,
            Collection<Long> groupIds, Date from, Date to) throws StorageException {
        reportUtils.checkPeriodLimit(from, to);
        ArrayList<Position> result = new ArrayList<>();
        for (Device device : DeviceUtil.getAccessibleDevices(storage, userId, deviceIds, groupIds)) {
            List<Position> rawPositions = PositionUtil.getPositions(storage, device.getId(), from, to);
            result.addAll(routePositions(rawPositions));
        }
        return result;
    }

    private String getUniqueSheetName(String key) {
        namesCount.compute(key, (k, value) -> value == null ? 1 : (value + 1));
        return namesCount.get(key) > 1 ? key + '-' + namesCount.get(key) : key;
    }

    public void getExcel(OutputStream outputStream,
            long userId, Collection<Long> deviceIds, Collection<Long> groupIds,
            Date from, Date to) throws StorageException, IOException {
        reportUtils.checkPeriodLimit(from, to);

        ArrayList<DeviceReportSection> devicesRoutes = new ArrayList<>();
        ArrayList<String> sheetNames = new ArrayList<>();

        for (Device device : DeviceUtil.getAccessibleDevices(storage, userId, deviceIds, groupIds)) {
            // [Patch-4] getPositions() returns a List<Position> — no need to cast
            List<Position> rawPositions = PositionUtil.getPositions(storage, device.getId(), from, to);

            // [Patch-5] Route via match/v1 (if enabled) or route/v1 — only in getExcel()
            List<Position> positions = routePositions(rawPositions);

            DeviceReportSection deviceRoutes = new DeviceReportSection();
            deviceRoutes.setDeviceName(device.getName());
            sheetNames.add(WorkbookUtil.createSafeSheetName(getUniqueSheetName(deviceRoutes.getDeviceName())));

            if (device.getGroupId() > 0) {
                Group group = storage.getObject(Group.class, new Request(
                        new Columns.All(), new Condition.Equals("id", device.getGroupId())));
                if (group != null) {
                    deviceRoutes.setGroupName(group.getName());
                }
            }

            deviceRoutes.setObjects(positions);
            devicesRoutes.add(deviceRoutes);
        }

        File file = Paths.get(config.getString(Keys.TEMPLATES_ROOT), "export", "route.xlsx").toFile();
        try (InputStream inputStream = new FileInputStream(file)) {
            var context = reportUtils.initializeContext(userId);
            context.putVar("devices", devicesRoutes);
            context.putVar("sheetNames", sheetNames);
            context.putVar("from", from);
            context.putVar("to", to);
            reportUtils.processTemplateWithSheets(inputStream, outputStream, context);
        }
    }
}
