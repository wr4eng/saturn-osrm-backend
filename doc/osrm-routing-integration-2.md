# OSRM Routing Integration — `match/v1` with `route/v1` Fallback

> Saturn GPS Tracking Server — fork of Traccar v6.12.2  
> Package: `org.saturn.routing` (or `org.traccar.routing` for upstream patch)  
> Status: Production — tested on Saturn v1.1.4

> Dev Repositories :

- `https://github.com/wr4eng/saturn-osrm-backend`
- `https://github.com/wr4eng/saturn-osrm-ui`

---

## Overview

This document describes the native OSRM routing integration added to the Saturn/Traccar
backend, comprising two complementary clients:

| Client | Endpoint | Algorithm | Use case |
|---|---|---|---|
| `OsrmClient` | `/route/v1` | Shortest path + drift pre-filter | Primary or fallback |
| `OsrmMatchClient` | `/match/v1` | Hidden Markov Model (HMM) | Primary when enabled |

When map matching is enabled, `OsrmMatchClient` is the primary router.
It automatically falls back to `OsrmClient` on `NoMatch`, low confidence,
or any HTTP failure — making the integration fully transparent to callers.

OSRM backend references : 

- https://project-osrm.org/docs/v26.4.0/
- https://github.com/Project-OSRM/osrm-backend

---

## Motivation

GPS devices in fleet environments frequently produce **GPS drift** — erroneous
coordinates caused by multipath, poor satellite geometry, or brief signal loss.
When these raw coordinates are fed directly to `/route/v1`, OSRM faithfully routes
through the drift points, producing loops and impossible paths on the map.

**Example observed in production** (My City - urban area):
```
Raw GPS sequence:  main road → parking lot loop → back to main road
/route/v1 output:  routes through the parking lot loop as if it were real travel
/match/v1 output:  drops the drift points (null tracepoints), stays on main road
```

`/match/v1` uses a Hidden Markov Model that evaluates the probability of each
GPS point belonging to each candidate road segment, considering both spatial distance
and implied travel speed between consecutive timestamps. Outlier points receive
near-zero probability and are dropped (returned as `null` tracepoints).

---

## Architecture

### File Structure

```
src/main/java/org/saturn/
src/main/java/org/traccar/ (upstream default)
│
├── config/
│   └── Keys.java                     ADD — 5 new routing config keys
│
├── routing/                          NEW package
│   ├── OsrmClient.java               NEW — /route/v1 + GPS drift pre-filter
│   └── OsrmMatchClient.java          NEW — /match/v1 + automatic fallback
│
├── reports/
│   └── RouteReportProvider.java      ADD — routePositions() dispatch method
│
└── MainModule.java                   ADD — Guice providers for both clients
```

### Request Flow

```
RouteReportProvider.routePositions(rawPositions)
    │
    ├─[match enabled]─▶ OsrmMatchClient.matchRoute()
    │                       │
    │                       ├─ filterValid()          drop invalid lat/lon
    │                       ├─ buildTimestampsString() fixTime → Unix seconds (ASC)
    │                       ├─ POST /match/v1/driving/...?tidy=true&gaps=split
    │                       │
    │                       ├─[code=Ok, confidence ≥ threshold]─▶ return matched positions
    │                       ├─[code=NoMatch]───────────────────▶ fallback  ─┐
    │                       ├─[confidence < threshold per segment]  ─────────┤
    │                       └─[HTTP error / parse error]  ───────────────────┤
    │                                                                        │
    └─[match disabled]─▶ OsrmClient.calculateRoute() ◀─────────────────────┘
                             │
                             ├─ applyDriftFilter()    speed + accuracy + distance
                             ├─ GET /route/v1/driving/...?overview=full&geometries=geojson
                             └─[any failure]─────────▶ return original positions
```

---

## Components

### `OsrmClient` — `/route/v1` with GPS Drift Pre-filter

Handles coordinate conversion, validation, HTTP retries, result caching,
and a three-stage drift filter applied **before** sending coordinates to OSRM.

#### Drift Filter Pipeline (`applyDriftFilter`)

Runs on every `calculateRoute()` call. All three stages are independently
configurable and can be disabled by setting their threshold to `0`.

**Stage 1 — Coordinate validity**  
Drop positions where `latitude` or `longitude` is outside valid WGS84 range.

**Stage 2 — GPS accuracy attribute**  
If the device sends an `accuracy` attribute (in meters), drop positions where
accuracy exceeds `routing.filter.maxAccuracy`. Devices using OsmAnd protocol
or the Saturn Client application send this attribute automatically.

**Stage 3 — Implied speed check**  
Compute the Haversine distance between consecutive accepted positions and
divide by the time delta from `fixTime`. Drop the newer position if the implied
speed exceeds `routing.filter.maxSpeed` km/h. This catches "teleportation"
drift — coordinates that jump far from the previous position.

**Stage 4 — Minimum distance**  
Skip positions closer than `routing.filter.minDistance` metres to the
previously accepted position. This removes stationary noise clusters where
the device oscillates around a fixed point.

```java
// Haversine distance in metres — used internally by drift filter
private static double haversineMeters(double lat1, double lon1,
                                       double lat2, double lon2)
```

#### Caching

Results are cached in a `ConcurrentHashMap<String, CacheEntry>` keyed by
a hash of `profile + coordinates (4 decimal places) + hour bucket`.

Cache TTL is controlled by `routing.cache.ttl` (default 1 hour).

---

### `OsrmMatchClient` — `/match/v1` with HMM

Uses OSRM's map matching API which applies a Hidden Markov Model to the
GPS trace. 
The HMM considers both the geometric distance from a candidate
road and the transition probability between consecutive road segments given
the elapsed time and implied speed.

#### Key Behaviours

**Timestamps** — extracted from `Position.getFixTime()` and converted to Unix
seconds. Timestamps **must be monotonically increasing**; if a non-monotonic
sequence is detected (e.g. positions out of order), timestamps are omitted
and OSRM matches without temporal constraints.

**`tidy=true`** — instructs OSRM to remove statistically implausible sub-traces
before matching, reducing `NoMatch` on traces with isolated drift clusters.

**`gaps=split`** — when the trace contains a temporal gap too large for a
continuous match, OSRM returns multiple independent `matchings` segments
rather than failing the entire request.

**Per-segment confidence filtering** — each segment in `matchings[]` has a
`confidence` field (0.0–1.0). Segments below `routing.match.minConfidence`
are discarded. If all segments are discarded, the fallback client is invoked.

**Null tracepoints** — positions dropped by HMM are returned as `null` entries
in the `tracepoints[]` array. These are logged at `INFO` level with their
original indices for diagnostic purposes.

#### Fallback Conditions

`OsrmMatchClient` delegates to `OsrmClient.calculateRoute()` when:

- OSRM returns `code: NoMatch` (trace too sparse or entirely outside road network)
- All `matchings[]` segments have `confidence < routing.match.minConfidence`
- HTTP request fails after all retry attempts
- JSON parse error in the response
- `filterValid()` yields fewer than 2 valid positions

The fallback client itself applies the drift filter pipeline before calling
`/route/v1`, providing a second layer of noise reduction.

---

### `RouteReportProvider` — Dispatch Method

A single private method `routePositions()` dispatches to the appropriate client,
used by all three public routing methods:

```java
private List<Position> routePositions(List<Position> rawPositions) {
    if (osrmMatchClient != null && rawPositions.size() >= 2) {
        return osrmMatchClient.matchRoute(rawPositions);      // match/v1 primary
    } else if (osrmClient != null && rawPositions.size() >= 2) {
        return osrmClient.calculateRoute(rawPositions);       // route/v1 fallback
    }
    return rawPositions;                                      // routing disabled
}
```

| Method | Called by | Routing applied |
|---|---|---|
| `getObjects()` | `GET /reports/route` (JSON) | **No** — returns raw positions for backward compatibility |
| `getObjectsSnapped()` | `GET /reports/route-snap` (JSON) | **Yes** — via `routePositions()` |
| `getExcel()` | `GET /reports/route` (Excel) | **Yes** — via `routePositions()` |

---

### `MainModule` — Guice Providers

Both clients follow the same nullable provider pattern used by `Geocoder`
and `SpeedLimitProvider` in Traccar:

```java
@Singleton @Provides
public static OsrmClient provideOsrmClient(Config config) {
    if ("osrm".equalsIgnoreCase(config.getString(Keys.ROUTING_TYPE))) {
        return new OsrmClient(config);
    }
    return null;
}

@Singleton @Provides
public static OsrmMatchClient provideOsrmMatchClient(
        Config config, @Nullable OsrmClient osrmClient) {
    if ("osrm".equalsIgnoreCase(config.getString(Keys.ROUTING_TYPE))
            && config.getBoolean(Keys.ROUTING_MATCH_ENABLED)) {
        return osrmClient != null ? new OsrmMatchClient(config, osrmClient) : null;
    }
    return null;
}
```

`OsrmMatchClient` receives `OsrmClient` as a constructor argument (not via
`@Inject`) to make the fallback relationship explicit and avoid circular
dependency issues.

---

## Configuration Reference

All keys are added to `Keys.java` and read from `traccar.xml` / `saturn.xml`.

### Base Routing Keys (required)

| Key | Type | Default | Description |
|---|---|---|---|
| `routing.type` | String | `none` | Set to `osrm` to enable |
| `routing.url` | String | `http://localhost:5000` | OSRM base URL |
| `routing.profile` | String | `driving` | OSRM routing profile |
| `routing.snap` | Boolean | `true` | Snap to nearest road |
| `routing.timeout` | Long | `30` | HTTP timeout in seconds |
| `routing.cache` | Boolean | `true` | Enable result caching |
| `routing.cache.ttl` | Long | `3600000` | Cache TTL in milliseconds |
| `routing.retry` | Integer | `3` | Max HTTP retry attempts |
| `routing.debug` | Boolean | `false` | Verbose routing log |

### Drift Filter Keys (`OsrmClient`)

| Key | Type | Default | Description |
|---|---|---|---|
| `routing.filter.maxSpeed` | Integer | `150` | Drop if implied speed > N km/h. Set `0` to disable. |
| `routing.filter.maxAccuracy` | Integer | `50` | Drop if GPS accuracy > N metres. Set `0` to disable. |
| `routing.filter.minDistance` | Integer | `10` | Skip if distance to prev < N metres. Set `0` to disable. |

### Map Matching Keys (`OsrmMatchClient`)

| Key | Type | Default | Description |
|---|---|---|---|
| `routing.match.enabled` | Boolean | `false` | Enable `/match/v1` as primary router |
| `routing.match.minConfidence` | Double | `0.5` | Minimum HMM confidence to accept a segment (0.0–1.0) |

### Recommended Configuration

```xml
<!-- traccar.xml / saturn.xml -->

<!-- Enable OSRM routing -->
<entry key='routing.type'>osrm</entry>
<entry key='routing.url'>http://localhost:5000</entry>
<entry key='routing.profile'>driving</entry>

<!-- Drift filter (route/v1 path) -->
<entry key='routing.filter.maxSpeed'>120</entry>
<entry key='routing.filter.maxAccuracy'>40</entry>
<entry key='routing.filter.minDistance'>15</entry>

<!-- Enable map matching (match/v1 path) -->
<entry key='routing.match.enabled'>true</entry>
<entry key='routing.match.minConfidence'>0.5</entry>
```

---

## Operational Notes

### Verifying Startup

Both clients log at `INFO` level during initialisation. Check the server log
after startup:

```
INFO: OSRM Client initialized: url=http://localhost:5000, profile=driving, cache=true, retries=3, timeout=30s
INFO: OSRM MatchClient initialized: url=http://localhost:5000, profile=driving, minConfidence=0.50
INFO: RouteReportProvider: using OsrmMatchClient (match/v1) as primary router
```

If only `OsrmClient initialized` appears, check that `routing.match.enabled=true`
is present in the configuration file and the server was restarted.

### Verifying Match Behaviour

When a route report is generated, the following lines appear in the log:

```
INFO:  MatchClient: 5/25 tracepoints dropped by HMM: [1, 10, 11, 23, 24]
INFO:  MatchClient: match complete — 1 segments accepted, 233 positions, 5 dropped
```

`dropped idx` lists the original position indices that OSRM identified as
outliers. A high drop count (> 30%) with low confidence typically indicates
the trace is too sparse for reliable matching; consider lowering
`routing.match.minConfidence` or disabling match for that deployment.

### Fallback Verification

To confirm the fallback path is working, temporarily set an unreachable URL:

```xml
<entry key='routing.url'>http://127.0.0.1:9999</entry>
```

The log should show:

```
WARNING: MatchClient: HTTP failed, using fallback
INFO:    MatchClient: executing fallback to OsrmClient (route/v1)
WARNING: OSRM request failed after retries, falling back to original positions
```

### OSRM Server Requirements

Both `/route/v1` and `/match/v1` must be enabled on the OSRM server.
Standard `osrm-routed` builds include both endpoints by default.

Verify match endpoint availability:
```bash
curl -s "http://localhost:5000/match/v1/driving/\
<lon1>,<lat1>;<lon2>,<lat2>;<lon3>,<lat3>\
?overview=false" | python3 -m json.tool
```

Expected response: `"code": "Ok"` or `"code": "NoMatch"` (not a 404 or
connection error).

---

## Test Results

Tested against a production OSRM v5.26.0 instance with real device trace
data (25 GPS positions, 4-hour urban trip, My City, Indonesia):

```
/match/v1 with real fixTime timestamps:
  code          : Ok
  matching [0]  : confidence=0.7148  geo_pts=233  dist_km=4.45   ← accepted
  matching [1]  : confidence=0.0002  geo_pts=300  dist_km=8.55   ← skipped (< 0.5)
  tracepoints   : 25
  dropped(null) : 5 / 25  idx: [1, 10, 11, 23, 24]
```

The 5 dropped tracepoints correspond precisely to the GPS drift cluster
that was producing an erroneous loop in the route visualisation when using
`/route/v1` directly.

---

## Patch Scope for Traccar Upstream

The following changes are self-contained and introduce no breaking changes
to existing Traccar behaviour:

1. **`Keys.java`** — 5 new `ConfigKey` entries in the routing section.
   All default to values that preserve current behaviour (`match.enabled=false`).

2. **`routing/OsrmClient.java`** — new class, new package.
   No existing class is modified.

3. **`routing/OsrmMatchClient.java`** — new class, same package.
   No existing class is modified.

4. **`reports/RouteReportProvider.java`** — minimal change:
   - Constructor gains two `@Nullable` parameters instead of one.
   - One private `routePositions()` dispatch method added.
   - `getExcel()` and `getObjectsSnapped()` each replace a 4-line
     `if (osrmClient != null)` block with a single `routePositions()` call.

5. **`MainModule.java`** — one additional `@Provides` method added.
   Existing `provideOsrmClient()` is unchanged.

---

*Document version: 2.0 — May 2026*  
*Implementation: Saturn GPS Server v1.1.4 (Traccar fork 6.12.2)*

- *Backend Repo: `athena.domain/wra_eng/saturn-osrm-backend`*
`https://github.com/wr4eng/saturn-osrm-backend`
- *UI Repo: `athena.domain/wra_eng/saturn-osrm-ui`*
`https://github.com/wr4eng/saturn-osrm-ui`

