// src/main/java/org/saturn/api/resource/ReportResource.java

package org.saturn.api.resource;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.core.Context;
import org.saturn.api.SimpleObjectResource;
import org.saturn.helper.LogAction;
import org.saturn.model.Event;
import org.saturn.model.Position;
import org.saturn.model.Report;
import org.saturn.model.UserRestrictions;
import org.saturn.reports.CombinedReportProvider;
import org.saturn.reports.DevicesReportProvider;
import org.saturn.reports.EventsReportProvider;
import org.saturn.reports.GeofenceReportProvider;
import org.saturn.reports.RouteReportProvider;
import org.saturn.reports.StopsReportProvider;
import org.saturn.reports.SummaryReportProvider;
import org.saturn.reports.TripxReportProvider;
import org.saturn.reports.TripxReportProvider.TableMatrixResult;
import org.saturn.reports.TripxReportProvider.TripPlanResult;
import org.saturn.reports.TripsReportProvider;
import org.saturn.reports.common.ReportExecutor;
import org.saturn.reports.common.ReportMailer;
import org.saturn.reports.model.CombinedReportItem;
import org.saturn.reports.model.GeofenceReportItem;
import org.saturn.reports.model.StopReportItem;
import org.saturn.reports.model.SummaryReportItem;
import org.saturn.reports.model.TripReportItem;
import org.saturn.storage.StorageException;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.stream.Stream;

@Path("reports")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ReportResource extends SimpleObjectResource<Report> {

    private static final String EXCEL = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Inject
    private CombinedReportProvider combinedReportProvider;

    @Inject
    private EventsReportProvider eventsReportProvider;

    @Inject
    private GeofenceReportProvider geofenceReportProvider;

    @Inject
    private RouteReportProvider routeReportProvider;

    @Inject
    private StopsReportProvider stopsReportProvider;

    @Inject
    private SummaryReportProvider summaryReportProvider;

    @Inject
    private TripsReportProvider tripsReportProvider;

    @Inject
    private DevicesReportProvider devicesReportProvider;
    
    // -------------------------------------------------------------------------
    // OsrmClient 2025 WrA (wra.eng@gmail.com)
    // TripxReportProvider (trip/v1 + table/v1)
    // GET /api/reports/tripx-snap?deviceId=={id}
    // GET /api/reports/table?deviceId=={id}
    // -------------------------------------------------------------------------
    @Inject
    private TripxReportProvider tripxReportProvider;

    @Inject
    private ReportMailer reportMailer;

    @Inject
    private LogAction actionLogger;

    @Context
    private HttpServletRequest request;

    public ReportResource() {
        super(Report.class, "description");
    }

    private Response executeReport(long userId, boolean mail, ReportExecutor executor) {
        if (mail) {
            reportMailer.sendAsync(userId, executor);
            return Response.noContent().build();
        } else {
            StreamingOutput stream = output -> {
                try {
                    executor.execute(output);
                } catch (StorageException e) {
                    throw new WebApplicationException(e);
                }
            };
            return Response.ok(stream)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=report.xlsx").build();
        }
    }

    @Path("combined")
    @GET
    public Collection<CombinedReportItem> getCombined(
            @QueryParam("deviceId") List<Long> deviceIds,
            @QueryParam("groupId") List<Long> groupIds,
            @QueryParam("from") Date from,
            @QueryParam("to") Date to) throws StorageException {
        permissionsService.checkRestriction(getUserId(), UserRestrictions::getDisableReports);
        actionLogger.report(request, getUserId(), false, "combined", from, to, deviceIds, groupIds);
        return combinedReportProvider.getObjects(getUserId(), deviceIds, groupIds, from, to);
    }

    @Path("route")
    @GET
    public Collection<Position> getRoute(
            @QueryParam("deviceId") List<Long> deviceIds,
            @QueryParam("groupId") List<Long> groupIds,
            @QueryParam("from") Date from,
            @QueryParam("to") Date to) throws StorageException {
        permissionsService.checkRestriction(getUserId(), UserRestrictions::getDisableReports);
        actionLogger.report(request, getUserId(), false, "route", from, to, deviceIds, groupIds);
        return routeReportProvider.getObjects(getUserId(), deviceIds, groupIds, from, to);
    }

    @Path("route")
    @GET
    @Produces(EXCEL)
    public Response getRouteExcel(
            @QueryParam("deviceId") List<Long> deviceIds,
            @QueryParam("groupId") List<Long> groupIds,
            @QueryParam("from") Date from,
            @QueryParam("to") Date to,
            @QueryParam("mail") boolean mail) throws StorageException {
        permissionsService.checkRestriction(getUserId(), UserRestrictions::getDisableReports);
        return executeReport(getUserId(), mail, stream -> {
            actionLogger.report(request, getUserId(), false, "route", from, to, deviceIds, groupIds);
            routeReportProvider.getExcel(stream, getUserId(), deviceIds, groupIds, from, to);
        });
    }

    @Path("route/{type:xlsx|mail}")
    @GET
    @Produces(EXCEL)
    public Response getRouteExcel(
            @QueryParam("deviceId") List<Long> deviceIds,
            @QueryParam("groupId") final List<Long> groupIds,
            @QueryParam("from") Date from,
            @QueryParam("to") Date to,
            @PathParam("type") String type) throws StorageException {
        return getRouteExcel(deviceIds, groupIds, from, to, type.equals("mail"));
    }

    // -------------------------------------------------------------------------
    // OsrmClient 2025 WrA (wra.eng@gmail.com)
    // route-snap (match/v1) (route/v1)
    // GET /api/reports/route-snap?deviceId=={id}
    // -------------------------------------------------------------------------
    @Path("route-snap")
    @GET
    public Collection<Position> getRouteSnap(
            @QueryParam("deviceId") List<Long> deviceIds,
            @QueryParam("groupId") List<Long> groupIds,
            @QueryParam("from") Date from,
            @QueryParam("to") Date to) throws StorageException {
        permissionsService.checkRestriction(getUserId(), UserRestrictions::getDisableReports);
        actionLogger.report(request, getUserId(), false, "route-snap", from, to, deviceIds, groupIds);
        return routeReportProvider.getObjectsSnapped(getUserId(), deviceIds, groupIds, from, to);
    }

    // -------------------------------------------------------------------------
    // OsrmClient 2025 WrA (wra.eng@gmail.com)
    // tripx-snap — MODE 1 (trip/v1) — optimized multi-stop route
    // GET /api/reports/tripx-snap?deviceId=1&deviceId=2&deviceId=3
    // No from/to — uses last known position from CacheManager per device
    // Returns TripPlanResult (stops in visit order + geometry)
    // -------------------------------------------------------------------------
    @Path("tripx-snap")
    @GET
    public Response getTripSnap(
            @QueryParam("deviceId") List<Long> deviceIds,
            @QueryParam("groupId") List<Long> groupIds) throws StorageException {
        permissionsService.checkRestriction(getUserId(), UserRestrictions::getDisableReports);
        //actionLogger.report(request, getUserId(), false, "tripx-snap", null, null, deviceIds, groupIds);
        Date now = new Date();
        actionLogger.report(request, getUserId(), false, "tripx-snap", now, now, deviceIds, groupIds);
        TripPlanResult result = tripxReportProvider.getObjectsTrip(getUserId(), deviceIds, groupIds);
        if (result == null) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity("{\"message\":\"Trip planning unavailable. "
                            + "Check routing.trip.enabled and routing.type=osrm\"}")
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }
        return Response.ok(result).build();
    }

    // -------------------------------------------------------------------------
    // OsrmClient 2025 WrA (wra.eng@gmail.com)
    // table — MODE 2 (table/v1) — ETA matrix vehicles vs geofences
    // GET /api/reports/table?deviceId=1&deviceId=2&geofenceId=10&geofenceId=11
    // No from/to — uses last known position from CacheManager per device
    // Returns TableMatrixResult (ETA matrix + nearest vehicle per geofence)
    // -------------------------------------------------------------------------
    @Path("table")
    @GET
    public Response getTable(
            @QueryParam("deviceId") List<Long> deviceIds,
            @QueryParam("groupId") List<Long> groupIds,
            @QueryParam("geofenceId") List<Long> geofenceIds) throws StorageException {
        permissionsService.checkRestriction(getUserId(), UserRestrictions::getDisableReports);
        //actionLogger.report(request, getUserId(), false, "table", null, null, deviceIds, groupIds);
        Date now = new Date();
        actionLogger.report(request, getUserId(), false, "table", now, now, deviceIds, groupIds);
        if (geofenceIds == null || geofenceIds.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"message\":\"At least one geofenceId is required\"}")
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }

        TableMatrixResult result = tripxReportProvider.getObjectsTable(
                getUserId(), deviceIds, groupIds, geofenceIds);
        if (result == null) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity("{\"message\":\"Table computation unavailable. "
                            + "Check routing.table.enabled and routing.type=osrm\"}")
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }
        return Response.ok(result).build();
    }

    @Path("events")
    @GET
    public Stream<Event> getEvents(
            @QueryParam("deviceId") List<Long> deviceIds,
            @QueryParam("groupId") List<Long> groupIds,
            @QueryParam("type") List<String> types,
            @QueryParam("alarm") List<String> alarms,
            @QueryParam("from") Date from,
            @QueryParam("to") Date to) throws StorageException {
        permissionsService.checkRestriction(getUserId(), UserRestrictions::getDisableReports);
        actionLogger.report(request, getUserId(), false, "events", from, to, deviceIds, groupIds);
        return eventsReportProvider.getObjects(getUserId(), deviceIds, groupIds, types, alarms, from, to);
    }

    @Path("events")
    @GET
    @Produces(EXCEL)
    public Response getEventsExcel(
            @QueryParam("deviceId") List<Long> deviceIds,
            @QueryParam("groupId") List<Long> groupIds,
            @QueryParam("type") List<String> types,
            @QueryParam("alarm") List<String> alarms,
            @QueryParam("from") Date from,
            @QueryParam("to") Date to,
            @QueryParam("mail") boolean mail) throws StorageException {
        permissionsService.checkRestriction(getUserId(), UserRestrictions::getDisableReports);
        return executeReport(getUserId(), mail, stream -> {
            actionLogger.report(request, getUserId(), false, "events", from, to, deviceIds, groupIds);
            eventsReportProvider.getExcel(stream, getUserId(), deviceIds, groupIds, types, alarms, from, to);
        });
    }

    @Path("events/{type:xlsx|mail}")
    @GET
    @Produces(EXCEL)
    public Response getEventsExcel(
            @QueryParam("deviceId") List<Long> deviceIds,
            @QueryParam("groupId") List<Long> groupIds,
            @QueryParam("type") List<String> types,
            @QueryParam("alarm") List<String> alarms,
            @QueryParam("from") Date from,
            @QueryParam("to") Date to,
            @PathParam("type") String type) throws StorageException {
        return getEventsExcel(deviceIds, groupIds, types, alarms, from, to, type.equals("mail"));
    }

    @Path("geofences")
    @GET
    public Collection<GeofenceReportItem> getGeofences(
            @QueryParam("deviceId") List<Long> deviceIds,
            @QueryParam("groupId") List<Long> groupIds,
            @QueryParam("geofenceId") List<Long> geofenceIds,
            @QueryParam("from") Date from,
            @QueryParam("to") Date to) throws StorageException {
        permissionsService.checkRestriction(getUserId(), UserRestrictions::getDisableReports);
        actionLogger.report(request, getUserId(), false, "geofences", from, to, deviceIds, groupIds);
        return geofenceReportProvider.getObjects(getUserId(), deviceIds, groupIds, geofenceIds, from, to);
    }

    @Path("summary")
    @GET
    public Collection<SummaryReportItem> getSummary(
            @QueryParam("deviceId") List<Long> deviceIds,
            @QueryParam("groupId") List<Long> groupIds,
            @QueryParam("from") Date from,
            @QueryParam("to") Date to,
            @QueryParam("daily") boolean daily) throws StorageException {
        permissionsService.checkRestriction(getUserId(), UserRestrictions::getDisableReports);
        actionLogger.report(request, getUserId(), false, "summary", from, to, deviceIds, groupIds);
        return summaryReportProvider.getObjects(getUserId(), deviceIds, groupIds, from, to, daily);
    }

    @Path("summary")
    @GET
    @Produces(EXCEL)
    public Response getSummaryExcel(
            @QueryParam("deviceId") List<Long> deviceIds,
            @QueryParam("groupId") List<Long> groupIds,
            @QueryParam("from") Date from,
            @QueryParam("to") Date to,
            @QueryParam("daily") boolean daily,
            @QueryParam("mail") boolean mail) throws StorageException {
        permissionsService.checkRestriction(getUserId(), UserRestrictions::getDisableReports);
        return executeReport(getUserId(), mail, stream -> {
            actionLogger.report(request, getUserId(), false, "summary", from, to, deviceIds, groupIds);
            summaryReportProvider.getExcel(stream, getUserId(), deviceIds, groupIds, from, to, daily);
        });
    }

    @Path("summary/{type:xlsx|mail}")
    @GET
    @Produces(EXCEL)
    public Response getSummaryExcel(
            @QueryParam("deviceId") List<Long> deviceIds,
            @QueryParam("groupId") List<Long> groupIds,
            @QueryParam("from") Date from,
            @QueryParam("to") Date to,
            @QueryParam("daily") boolean daily,
            @PathParam("type") String type) throws StorageException {
        return getSummaryExcel(deviceIds, groupIds, from, to, daily, type.equals("mail"));
    }

    @Path("trips")
    @GET
    public Collection<TripReportItem> getTrips(
            @QueryParam("deviceId") List<Long> deviceIds,
            @QueryParam("groupId") List<Long> groupIds,
            @QueryParam("from") Date from,
            @QueryParam("to") Date to) throws StorageException {
        permissionsService.checkRestriction(getUserId(), UserRestrictions::getDisableReports);
        actionLogger.report(request, getUserId(), false, "trips", from, to, deviceIds, groupIds);
        return tripsReportProvider.getObjects(getUserId(), deviceIds, groupIds, from, to);
    }

    @Path("trips")
    @GET
    @Produces(EXCEL)
    public Response getTripsExcel(
            @QueryParam("deviceId") List<Long> deviceIds,
            @QueryParam("groupId") List<Long> groupIds,
            @QueryParam("from") Date from,
            @QueryParam("to") Date to,
            @QueryParam("mail") boolean mail) throws StorageException {
        permissionsService.checkRestriction(getUserId(), UserRestrictions::getDisableReports);
        return executeReport(getUserId(), mail, stream -> {
            actionLogger.report(request, getUserId(), false, "trips", from, to, deviceIds, groupIds);
            tripsReportProvider.getExcel(stream, getUserId(), deviceIds, groupIds, from, to);
        });
    }

    @Path("trips/{type:xlsx|mail}")
    @GET
    @Produces(EXCEL)
    public Response getTripsExcel(
            @QueryParam("deviceId") List<Long> deviceIds,
            @QueryParam("groupId") List<Long> groupIds,
            @QueryParam("from") Date from,
            @QueryParam("to") Date to,
            @PathParam("type") String type) throws StorageException {
        return getTripsExcel(deviceIds, groupIds, from, to, type.equals("mail"));
    }

    @Path("stops")
    @GET
    public Collection<StopReportItem> getStops(
            @QueryParam("deviceId") List<Long> deviceIds,
            @QueryParam("groupId") List<Long> groupIds,
            @QueryParam("from") Date from,
            @QueryParam("to") Date to) throws StorageException {
        permissionsService.checkRestriction(getUserId(), UserRestrictions::getDisableReports);
        actionLogger.report(request, getUserId(), false, "stops", from, to, deviceIds, groupIds);
        return stopsReportProvider.getObjects(getUserId(), deviceIds, groupIds, from, to);
    }

    @Path("stops")
    @GET
    @Produces(EXCEL)
    public Response getStopsExcel(
            @QueryParam("deviceId") List<Long> deviceIds,
            @QueryParam("groupId") List<Long> groupIds,
            @QueryParam("from") Date from,
            @QueryParam("to") Date to,
            @QueryParam("mail") boolean mail) throws StorageException {
        permissionsService.checkRestriction(getUserId(), UserRestrictions::getDisableReports);
        return executeReport(getUserId(), mail, stream -> {
            actionLogger.report(request, getUserId(), false, "stops", from, to, deviceIds, groupIds);
            stopsReportProvider.getExcel(stream, getUserId(), deviceIds, groupIds, from, to);
        });
    }

    @Path("stops/{type:xlsx|mail}")
    @GET
    @Produces(EXCEL)
    public Response getStopsExcel(
            @QueryParam("deviceId") List<Long> deviceIds,
            @QueryParam("groupId") List<Long> groupIds,
            @QueryParam("from") Date from,
            @QueryParam("to") Date to,
            @PathParam("type") String type) throws StorageException {
        return getStopsExcel(deviceIds, groupIds, from, to, type.equals("mail"));
    }

    @Path("devices/{type:xlsx|mail}")
    @GET
    @Produces(EXCEL)
    public Response getDevicesExcel(
            @PathParam("type") String type) throws StorageException {
        permissionsService.checkRestriction(getUserId(), UserRestrictions::getDisableReports);
        return executeReport(getUserId(), type.equals("mail"), stream -> {
            devicesReportProvider.getExcel(stream, getUserId());
        });
    }

}
