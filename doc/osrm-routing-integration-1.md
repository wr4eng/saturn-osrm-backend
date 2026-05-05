# Saturn GPS — OSRM Snap-to-Road Integration
## Implementation Resume v1.1.1

> Fork of Traccar v6.12.2 | Custom build: `saturn-osrm-backend` (backend) + `saturn-osrm-ui` (frontend)

> Implementation: April 2026 | Status: **Production**

> React UI - https://github.com/wr4eng/saturn-osrm-ui.git

> Backend Java - https://github.com/wr4eng/saturn-osrm-backend.git

---

## I. OVERVIEW

Integration of the native OSRM routing engine into the Saturn backend and UI
enables route reports and trip reports to display GPS positions that
have been snapped to the road network, rather than Haversine algorithm raw GPS coordinates.

```

Device GPS  →  Saturn Backend  →  OSRM Engine  →  Snapped Positions  →  UI Map
(raw lat/lon)   (OsrmClient)       (sirius:5000)   (on-road coords)    (blue polyline)
                (OsrmMatchClient)
```

OSRM Infrastructure:

- Engine    : `sirius.domain:5000`
- TLS proxy : Caddy (port 5000 → 127.0.0.1:5001)
- Container : Docker, `osrm-routed` v5.26.0
- Endpoint  : `/route/v1/driving/...` (GeoJSON overview=full)
- Endpoint  : `/match/v1/driving/...` (GeoJSON overview=full)

---

## II. BACKEND — saturn (Java / Guice)


```
src/main/java/org/saturn/
│
├── config/
│   └── Keys.java                         MODIFIED
│       └── + ROUTING_TYPE                "routing.type"        default: "none" (none, osrm, graphhopper, valhalla)
│       └── + ROUTING_URL                 "routing.url"         default: "http://localhost:5000"
│       └── + ROUTING_PROFILE             "routing.profile"     default: "driving"
│       └── + ROUTING_SNAP                "routing.snap"        default: true
│       └── + ROUTING_TIMEOUT             "routing.timeout"     default: 30 (seconds)
│       └── + ROUTING_CACHE               "routing.cache"       default: true
│       └── + ROUTING_CACHE_TTL           "routing.cache.ttl"   default: 3600000 (ms)
│       └── + ROUTING_RETRY               "routing.retry"       default: 3
│       └── + ROUTING_DEBUG               "routing.debug"       default: false
│       └── + ROUTING_FILTER_MAX_SPEED    "routing.filter.maxSpeed"       default: 150
│       └── + ROUTING_FILTER_MAX_ACCURACY "routing.filter.maxAccuracy"    default: 50
│       └── + ROUTING_FILTER_MIN_DISTANCE "routing.filter.minDistance"    default: 10
│       └── + ROUTING_MATCH_ENABLED        "routing.match.enabled"        default: false
│       └── + ROUTING_MATCH_MIN_CONFIDENCE "routing.match.minConfidence"  default: 0.5 (0.0 - 1.0)
│
├── routing/                              NEW package
│   └── OsrmClient.java                   NEW Parse the /route/v1 response + drift filter
│       ├── calculateRoute(List<Position>) → List<Position>
│       │     Validate coordinates → cache check → build URL → HTTP retry
│       │     → parse GeoJSON response → createSnappedPosition()
│       ├── executeWithRetry(url)          exponential backoff 1s/2s/3s
│       ├── parseOsrmResponse()            OSRM [lon,lat] → Saturn lat/lon
│       ├── buildCoordinatesString()       Saturn lat,lon → OSRM lon,lat
│       ├── generateCacheKey()             profile + coords + hour bucket
│       ├── CacheEntry                     inner class, ConcurrentHashMap TTL
│       ├── filterMaxSpeedKmh              getInteger(Keys.ROUTING_FILTER_MAX_SPEED)
│       ├── filterMaxAccuracyM             getInteger(Keys.ROUTING_FILTER_MAX_ACCURACY)
│       ├── filterMinDistanceM             getInteger(Keys.ROUTING_FILTER_MIN_DISTANCE)
│       │
│   └── OsrmMatchClient.java               NEW Parse the /match/v1 response
│       ├── calculateRoute(List<Position>) → List<Position>
│       │     Validate coordinates → cache check → build URL → HTTP retry
│       │     → parse GeoJSON response → createSnappedPosition()
│       ├── matchRoute()                   filterValid, fallback, buildCoordinatesString,executeWithRetry 
│       ├── parseMatchResponse()           matchings, tracepoints, fallback
│       ├── buildCoordinatesString()       Saturn lat,lon → OSRM lon,lat
│       ├── buildTimestampsString()        
│       ├── coordsToPositions              original positions using an index offset
│
├── reports/
│   └── RouteReportProvider.java          MODIFIED
│       ├── field: OsrmClient osrmClient  @Nullable, injected via Guice
│       ├── getObjects()                  No change → raw positions (JSON API)
│       ├── getObjectsSnapped()           NEW → calculateRoute() per device
│       └── getExcel()                    MODIFIED → calculateRoute() before the template
│
│       ├── field: OsrmMatchClient osrmMatchClient  @Nullable, injected via Guice
│       ├── logging OsrmMatchClient (match/v1) or OsrmClient (route/v1)
│       ├── getObjects()                  No change → raw positions (JSON API)
│       ├── getObjectsSnapped()           NEW → calculateRoute() per device to endpoint /reports/route-snap
│       └── getExcel() match/v1 if enable then route/v1 fallback
│   
├── api/resource/
│   └── ReportResource.java               MODIFIED
│       ├── GET /reports/route            existing → getObjects() raw (unchanged)
│       ├── GET /reports/route (EXCEL)    existing → getExcel() with snap
│       └── GET /reports/route-snap       NEW → getObjectsSnapped() snapped JSON
│
└── MainModule.java                       MODIFIED
    └── @Provides @Singleton
        provideOsrmClient(Config config)
        → return new OsrmClient(config) if routing.type = “osrm”
        → return null if disabled (Geocoder/SpeedLimitProvider pattern)
        @Singleton @Provides
        provideOsrmMatchClient(Config config, @Nullable OsrmClient osrmClient)
        → enabled via routing.match.enabled=true
        → fallback into OsrmClient if routing disabled / match not enabled


```

### `conf/saturn.xml` Configuration

```xml
<entry key='routing.type'>osrm</entry>
<entry key='routing.url'>https://sirius.domain:5000</entry>
<entry key='routing.profile'>driving</entry>
<entry key='routing.snap'>true</entry>
<entry key='routing.cache'>true</entry>
<entry key='routing.cache.ttl'>3600000</entry>
<entry key='routing.timeout'>30</entry>
<entry key='routing.retry'>3</entry>

<!-- OsrmClient filter -->
<entry key='routing.filter.maxSpeed'>120</entry>
<entry key='routing.filter.maxAccuracy'>40</entry>
<entry key='routing.filter.minDistance'>15</entry>

<!-- OsrmMatchClient -->
<entry key='routing.match.enabled'>true</entry>
<entry key='routing.match.minConfidence'>0.5</entry>
    
```

### Backend Data Flow

```
ReportResource.getRouteSnap()
  └→ RouteReportProvider.getObjectsSnapped(userId, deviceIds, from, to)
       └→ PositionUtil.getPositions(storage, deviceId, from, to)   → 

List<Position> raw
       └→ OsrmClient.calculateRoute(rawPositions)
            ├→ filter invalid coords
            ├→ cache lookup (ConcurrentHashMap, TTL 1 hour)
            ├→ buildCoordinatesString() → “lon1,lat1;lon2,lat2;...”
            ├→ GET /route/v1/driving/...?overview=full&geometries=geojson
            ├→ executeWithRetry() max 3x, backoff 1s/2s/3s
            ├→ parseOsrmResponse() → map GeoJSON coords → Saturn Position
            └→ createSnappedPosition() → copy original metadata + new lat/lon

List<Position> raw
       └→ OsrmMatchClient.matchRoute()   ← match/v1 + HMM
          │    └─ fallback: OsrmClient.calculateRoute()  ← route/v1 + drift filter
          └─ (if match disabled) OsrmClient.calculateRoute()               

ReportResource.getRouteExcel() (existing, unchanged endpoint)
  └→ RouteReportProvider.getExcel()
       └→ OsrmClient.calculateRoute() → snapped positions
          OsrmMatchClient.matchRoute()   ← match/v1 + HMM
          │    └─ fallback: OsrmClient.calculateRoute()  ← route/v1 + drift filter
          └─ (if match disabled) OsrmClient.calculateRoute()      

```

### Additional dependencies in `build.gradle`

```groovy
implementation 'org.json:json:20240303'   // JSON parsing OSRM response
```

---

## III. FRONTEND — saturn-osrm-ui (React / MapLibre GL)

```
src/
│
├── Navigation.jsx                        MODIFIED
│   ├── + import RouteSnapReportPage
│   ├── + import TripSnapReportPage
│   ├── + import ReplayPageSnap
│   ├── + <Route path="reports/route-snap" element={<RouteSnapReportPage />} />
│   ├── + <Route path="reports/trip-snap"  element={<TripSnapReportPage />} />
│   └── + <Route path="replay-snap"        element={<ReplayPageSnap />} />
│
├── reports/
│   ├── RouteSnapReportPage.jsx           NEW
│   │   ├── fetch: GET /api/reports/route-snap (Accept: application/json)
│   │   ├── deviceType: "single", show-only (without onExport)
│   │   ├── layout: resizable map/table via useResizableLayout
│   │   ├── map: MapRoutePathSnap + MapRoutePointsSnap + MapPositions
│   │   └── table: column [fixTime, latitude, longitude, speed, address]
│   │
│   ├── TripSnapReportPage.jsx            NEW
│   │   ├── fetch trips: GET /api/reports/trips
│   │   ├── fetch route per trip: GET /api/reports/route-snap
│   │   ├── deviceType: "multiple", show-only (without onExport)
│   │   ├── layout: resizable map/table via useResizableLayout
│   │   ├── map: MapRoutePathSnap + MapMarkers (start/finish)
│   │   ├── table: ColumnSelect (startTime, endTime, distance, avgSpeed, ...)
│   │   └── navigate → /replay-snap per trip row
│   │
│   └── components/
│       └── ReportsMenu.jsx               MODIFIED
│           ├── + import AddRoadIcon      → Route (Snap to Road)
│           ├── + import AltRouteIcon     → Trip (Snap to Road)
│           ├── + MenuItem reportRouteSnap  → /reports/route-snap
│           ├── + MenuItem reportTripSnap   → /reports/trip-snap
│           └── buildLink() → route-snap is added to the single-device path list
│
├── other/
│   └── ReplayPageSnap.jsx                NEW
│       ├── fetch: GET /api/reports/route-snap (snapped positions)
│       ├── mirror ReplayPage.jsx
│       ├── map: MapRoutePathSnap + MapRoutePointsSnap
│       ├── playback: Slider + Play/Pause/FF/RW
│       ├── toolbar badge: “snap” (green) — visual distinction from ReplayPage
│       └── no DownloadIcon (KML export is not relevant for snapped data)
│
├── map/
│   ├── MapRoutePathSnap.js               NEW
│   │   ├── color: #1565C0 (solid blue, not a speed gradient)
│   │   ├── line-width: 4px (thicker than the default 2px)
│   │   ├── outline layer: white, width+2, opacity 0.4 (contrast with all basemaps)
│   │   └── do not use reportColor / getSpeedColor
│   │
│   └── MapRoutePointsSnap.js             NEW
│       ├── type: circle layer (not ▲ text symbol)
│       ├── circle-radius: 6px (scaled, vs original text-size:12)
│       ├── color: #1565C0 + white stroke 1.5px
│       ├── halo layer: white circle radius 8, opacity 0.6
│       └── without SpeedLegendControl
│
└── reports/common/
    ├── useResizableLayout.js             NEW
    │   ├── drag divider map/table (mousedown + touchstart)
    │   ├── ratio range: 0.1 – 0.9
    │   ├── default ratio: 0.6 (60% map)
    │   └── persist to localStorage key: "reportLayoutRatio"
    │
    └── useReportStyles.js                MODIFIED
        ├── containerMapResizable         flex-based inline styles (dynamic ratio)
        ├── resizeDivider                 6px, row-resize cursor, hover effect
        ├── resizeDividerHandle           32x3px bar, color transition
        ├── containerMainResizable        flex: 1 1 0, overflow: auto
        ├── resizePresetButtons           top-right absolute positioning above the map
        └── resizePresetButton            ▲ — ▼ buttons with preset ratios of 80/50/25%

```

### Additional i18n keys in `src/resources/l10n/en.json`

```json
"reportRouteSnap":  "Route (Snap)",
"reportTripSnap":   "Trip (Snap)",
"reportReplaySnap": "Replay (Snap)"
```

### Frontend Data Flow

```
User clicks Show on the RouteSnapReportPage
  └→ GET /api/reports/route-snap?deviceId=X&from=...&to=...
       └→ [snapped positions JSON]
  └→ setItems(data)
  └→ MapRoutePathSnap renders a blue polyline (snapped coordinates)
  └→ MapRoutePointsSnap renders blue circle waypoints
  └→ Table rows → click LocationSearchingIcon → MapPositions highlight

User clicks Show on TripSnapReportPage
  └→ GET /api/reports/trips?deviceId=X&from=...&to=...
       └→ [trip list JSON]
  └→ User clicks row → LocationSearchingIcon
       └→ GET /api/reports/route-snap?deviceId=X&from=startTime&to=endTime
            └→ [snapped route JSON for this trip]
       └→ MapRoutePathSnap + MapMarkers (start/finish flags)
  └→ User clicks row → RouteIcon
       └→ navigate(‘/replay-snap?deviceId=X&from=...&to=...’)
            └→ ReplayPageSnap → fetch route-snap → playback

```
### UI flow
```

UI  →  /api/reports/route-snap
     →  RouteReportProvider.getObjectsSnapped()
     →  routePositions()
          ├─ OsrmMatchClient.matchRoute()   ← match/v1 + HMM
          │    └─ fallback: OsrmClient.calculateRoute()  ← route/v1 + drift filter
          └─ (if match disabled) OsrmClient.calculateRoute()

```

---

## IV. BUILD & DEPLOYMENT

### Build script `build-pack.sh`

```bash
./build-pack.sh              # Build without a version bump
./build-pack.sh --patch      # X.Y.Z → X.Y.Z+1
./build-pack.sh --minor      # X.Y.Z → X.Y+1.0
./build-pack.sh --major      # X.Y.Z → X+1.0.0
```

A script that reads and writes `“Implementation-Version”` in `build.gradle`,
performs a Gradle build, and then packs `target/conf/templates/schema/`
into `release/saturn-{VERSION}.7z` with an SHA256 checksum.

### Version history

| Version | Type  | Changes                                              |
|---------|-------|--------------------------------------------------------|
| 1.0.0  | base  | Saturn fork of Traccar v6.12.2                       |
| 1.0.1  | patch | Initial OsrmClient integration (Logger/Config build fix)    |
| 1.1.0  | patch | Remove debug attributes (fix ClassCastException WARN)   |
| 1.1.1  | minor | UI snap pages + resizable layout + custom map layers   |
| 1.1.2  | patch | add route filter OsrmClient (v2) |
| 1.1.4  | patch | add match OsrmMatchClient (v2) |
---

## V. IMPLEMENTATION NOTES

### Design Decisions

**Option selected (backend-driven snap)** — OsrmClient runs on the backend,
not in the browser. 

Reasons: performance (one request per report, not
per-point), consistency (all clients receive the same data),
and no public exposure of OSRM URLs.

**Separate endpoint `/route-snap`** — the existing `/route` endpoint remains unchanged
for backward compatibility with other integrations (Node-RED, API consumers).

**`getObjects()` remains raw** — only `getObjectsSnapped()` and `getExcel()`
implement routing, so the JSON API for live tracking is not affected.

**Separate map layers** — `MapRoutePathSnap` and `MapRoutePointsSnap` are created
as new files, without modifying `MapRoutePath` and `MapRoutePoints`
used by existing report pages.

### Known issues / limitations

- OSRM `route/v1` provides an interpolated path (not an exact GPS match),
  accuracy depends on the density of OSM map data in that area
- OSRM `/match/v1` is specifically designed for GPS traces using a Hidden Markov Model, it can detect and ignore coordinate outliers.
- `match/v1` used to reduce or correct GPS drift caused by certain factors, which can reduce the accuracy of the actual position
- `confidence > 0.5` Match works well with timestamps — optimal hierarchical implementation. OSRM automatically drops drift points (null tracepoints) using HMM; the confidence score indicates how confident it is in the results.
- `confidence > 0.5` → implement hierarkis match-first + fallback route
- `confidence < 0.1` → keep the existing route/v1 + drift filter, since this device's trace is indeed too noisy for an HMM
- Cache keys are based on hourly buckets — the same position at different times
  will generate different cache entries

---
### Backend Log 
 
```

2026-04-28 18:47:30  INFO: Command execution complete
2026-04-28 18:47:32  INFO: jetty-12.1.6; built: 2026-01-27T18:53:19.182Z; git: 88ca559572b1c8858b3c5684bb0293fa64e5e90f; jvm 21.0.10+7-LTS
2026-04-28 18:47:32  INFO: Session workerName=node0
2026-04-28 18:47:32  INFO: Started oeje10s.ServletContextHandler@43423d40{ROOT,/,b=file:///opt/saturn/web/,a=AVAILABLE,h=oeje10s.SessionHandler@5f3f57ff{STARTED}}
2026-04-28 18:47:34  INFO: Started oeje10s.ServletContextHandler@43423d40{ROOT,/,b=file:///opt/saturn/web/,a=AVAILABLE,h=oeje10s.SessionHandler@5f3f57ff{STARTED}}
2026-04-28 18:47:34  INFO: Started oejs.ServerConnector@4e1b5532{HTTP/1.1, (http/1.1)}{0.0.0.0:8082}
2026-04-28 18:47:34  INFO: Started oejs.Server@366bf608{STARTING}[12.1.6,sto=0] @1805ms
2026-04-28 18:48:27  INFO: OSRM Client initialized: url=https://sirius.domain:5000, profile=driving, cache=true, retries=3, timeout=30s

v2. with OsrmClient filter

2026-04-30 18:35:59  INFO: OSRM Client initialized: url=https://sirius.domain:5000, profile=driving, cache=true, retries=3, timeout=30s
2026-04-30 18:35:59  INFO: Drift filter: 10/30 positions dropped, 20 passed
2026-04-30 19:39:21  INFO: Drift filter: 1/4 positions dropped, 3 passed
2026-04-30 19:39:32  INFO: Drift filter: 1/3 positions dropped, 2 passed
2026-04-30 19:39:41  INFO: Drift filter: 10/30 positions dropped, 20 passed

v2 OsrmMatchClient OsrmClient fallback minconfidence=1.0

2026-05-01 20:15:09  INFO: OSRM MatchClient initialized: url=https://sirius.domain:5000, profile=driving, minConfidence=1.00
2026-05-01 20:17:10  INFO: RouteReportProvider: using OsrmMatchClient (match/v1) as primary router
2026-05-01 20:17:10  INFO: MatchClient: 15/48 tracepoints dropped by HMM: [0, 1, 2, 3, 4, 6, 15, 16, 28, 29, 32, 33, 43, 46, 47]
2026-05-01 20:17:10  INFO: MatchClient: segment [0] confidence=0.7148 < 1.00 — skip segment
2026-05-01 20:17:10  INFO: MatchClient: segment [1] confidence=0.0002 < 1.00 — skip segment
2026-05-01 20:17:10  INFO: MatchClient: segment [2] confidence=0.9784 < 1.00 — skip segment
2026-05-01 20:17:10  INFO: MatchClient: segment [3] confidence=0.0000 < 1.00 — skip segment
2026-05-01 20:17:10  WARN: MatchClient: all segments below confidence threshold — fallback
2026-05-01 20:17:10  INFO: MatchClient: executing fallback to OsrmClient (route/v1)
2026-05-01 20:17:10  INFO: Drift filter: 14/48 positions dropped, 34 passed

v2 OsrmMatchClient OsrmClient fallback minconfidence=0.5

2026-05-01 19:43:08  INFO: OSRM MatchClient initialized: url=https://sirius.domain:5000, profile=driving, minConfidence=0.50
2026-05-01 19:43:08  INFO: RouteReportProvider: using OsrmMatchClient (match/v1) as primary router
2026-05-01 19:43:09  INFO: MatchClient: 15/48 tracepoints dropped by HMM: [0, 1, 2, 3, 4, 6, 15, 16, 28, 29, 32, 33, 43, 46, 47]
2026-05-01 19:43:09  INFO: MatchClient: segment [1] confidence=0.0002 < 0.50 — skip segment
2026-05-01 19:43:09  INFO: MatchClient: segment [3] confidence=0.0000 < 0.50 — skip segment
2026-05-01 19:43:09  INFO: MatchClient: match complete — 2 segments accepted, 252 positions, 15 dropped

2026-05-01 19:44:55  INFO: RouteReportProvider: using OsrmMatchClient (match/v1) as primary router
2026-05-01 19:44:55  INFO: MatchClient: 4/15 tracepoints dropped by HMM: [3, 4, 5, 6]
2026-05-01 19:44:55  INFO: MatchClient: segment [0] confidence=0.0016 < 0.50 — skip segment
2026-05-01 19:44:55  WARN: MatchClient: all segments below confidence threshold — fallback
2026-05-01 19:44:55  INFO: MatchClient: executing fallback to OsrmClient (route/v1)


```

### OSRM Backend - Check `From Backend to OSRM endpoint`

```

curl -s "https://sirius.domain:5000/route/v1/driving/106.8456,-6.2088;106.8650,-6.1751?overview=false" | python3 -m json.tool | head -5

```

### TEST with `curl`
- Log in first, then get a `token`

```
curl -s -X POST "https://saturn.domain/api/session" \
-H "Content-Type: application/x-www-form-urlencoded" \
-d "email=YOUR_EMAIL&password=YOUR_PASS" -c /tmp/saturn-cookie.txt

```
```
curl -v -X GET \
"https://saturn.domain/api/reports/route?deviceId=3&from=2026-04-20T02:00:00Z&to=2026-04-20T04:00:00Z" \
-H "Accept: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" \
-b /tmp/saturn-cookie.txt \
-o /tmp/route-test.xlsx
  
```
### Result check

find :  __routed=true__

```

routed=true routedIndex=0  osrmProfile=driving
routed=true  routedIndex=1  osrmProfile=driving
routed=true  routedIndex=998  osrmProfile=driving
routed=true  routedIndex=999  osrmProfile=driving

```


---
*Generated: April 2026 | Repo: `athena.domain/wra_eng/saturn-osrm-backend`*
*UI Repo: `athena.domain/wra_eng/saturn-osrm-ui`*
