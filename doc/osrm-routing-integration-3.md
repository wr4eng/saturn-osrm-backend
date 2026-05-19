
#### Main

```
src/main/java/org/saturn/
│
├── config/
│   └── Keys.java                         MODIFIED
│       ├── + ROUTING_TRIP_ENABLED        "routing.trip.enabled"           default: false
│       ├── + ROUTING_TRIP_ROUNDTRIP      "routing.trip.roundtrip"         default: true
│       ├── + ROUTING_TRIP_SOURCE         "routing.trip.source"            default: "first"
│       ├── + ROUTING_TRIP_DESTINATION    "routing.trip.destination"       default: "last"
│       ├── + ROUTING_TABLE_ENABLED       "routing.table.enabled"          default: false
│       └── + ROUTING_TABLE_FALLBACK_SPEED "routing.table.fallbackSpeed"   default: 0
│
├── routing/
│   ├── OsrmClient.java                   ✔️
│   ├── OsrmMatchClient.java              ✔️
│   ├── OsrmTripClient.java               ✔️
│   └── OsrmTableClient.java              ✔️
│
├── reports/
│   ├── RouteReportProvider.java          ✔️
│   └── TripxReportProvider.java          ✔️
│       ├── OsrmTripClient  → /trip/v1   (trip planning)
│       └── OsrmTableClient → /table/v1  (ETA matrix vs geofence)
│
├── api/resource/
│   └── ReportResource.java               
│       ├── GET /reports/route-snap       ✔️
│       ├── GET /reports/tripx-snap       ✔️ → TripxReportProvider
│       └── GET /reports/table            ✔️ → TripxReportProvider (table mode)
│
├── utils/
│   └── GeofenceUtils.java                ✔️
│       ├── centroid(String wktPolygon) → Coordinate
│       ├── parsePolygon(String wkt)    → List<Coordinate>
│       └── isPolygon(String wkt)       → boolean
│
└── MainModule.java                       MODIFIED
    ├── provideOsrmTripClient(Config, @Nullable OsrmClient)
    │   → enabled via routing.trip.enabled=true
    └── provideOsrmTableClient(Config, @Nullable OsrmClient)
        → enabled via routing.table.enabled=true

```

#### TripxReportProvider.java

```
TripxReportProvider.java
│
├── mode: TRIP
│   ├── input  : List<Position> last position per device
│   ├── client : OsrmTripClient → /trip/v1
│   └── output : optimal visit order + route geometry
│
└── mode: TABLE
    ├── input  : List<Position> vehicles + List<Geofence> as destinations
    ├── client : OsrmTableClient → /table/v1
    ├── util   : GeofenceUtils.centroid() per geofence
    └── output : ETA matrix + nearest vehicle per geofence

```

#### OsrmTripClient.java

```
routing/
└── OsrmTripClient.java
    ├── Constructor: OsrmTripClient(Config config)
    ├── dependency: OsrmClient (reuse http client / config)
    │
    ├── planTrip(List<Position> lastPositions)
    │   └── hit /trip/v1/{profile}/{coords}
    │       params: roundtrip, source, destination, steps, geometries, overview
    │   └── return TripResult
    │       ├── List<TripLeg> legs (distance, duration, geometry)
    │       ├── List<WaypointOrder> waypoints (trips_index, waypoint_index)
    │       └── double totalDistance / totalDuration
    │
    └── fallback: jika NoTrips → fallback ke OsrmClient.calculateRoute() ?

```

#### OsrmTableClient.java

```
routing/
└── OsrmTableClient.java
    ├── Constructor: OsrmTableClient(Config config)
    │
    ├── computeMatrix(List<Coordinate> sources, List<Coordinate> destinations)
    │   └── hit /table/v1/{profile}/{coords}
    │       params: sources indices, destinations indices, annotations=duration,distance
    │   └── return TableResult
    │       ├── double[][] durations  (seconds)
    │       ├── double[][] distances  (meters)
    │       ├── List<Coordinate> sources (snapped)
    │       └── List<Coordinate> destinations (snapped)
    │
    ├── findNearest(Coordinate vehicle, List<Coordinate> destinations)
    │   └── shortcut: sources=single vehicle, destinations=all geofence centroids
    │   └── return sorted List<DestinationETA> by duration
    │
    └── GeofenceUtils.centroid(String wktPolygon) → Coordinate
        └── parse POLYGON WKT → average lat/lon

```

#### Keys.java

```
routing.trip.enabled        default: false
routing.trip.roundtrip      default: true
routing.trip.source         default: "first"
routing.trip.destination    default: "last"
routing.table.enabled       default: false
routing.table.fallback_speed default: 0.0 (disabled)

```

#### saturn.xml

```
<!-- OsrmTripClient - trip/v1 -->
<entry key='routing.trip.enabled'>true</entry>
<entry key='routing.trip.roundtrip'>true</entry>
<entry key='routing.trip.source'>first</entry>
<entry key='routing.trip.destination'>last</entry>

<!-- OsrmTableClient - table/v1 -->
<entry key='routing.table.enabled'>true</entry>
<entry key='routing.table.fallbackSpeed'>0.0</entry>

```
