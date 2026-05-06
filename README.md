# SATURN BACKEND OSRM
GPS Tracking Backend

Documentation : doc/
> Dev Repositories :

- `https://github.com/wr4eng/saturn-osrm-backend`
- `https://github.com/wr4eng/saturn-osrm-ui`

## Overview
```

- Gradle 9.3.1
- Kotlin:        2.2.21
- Groovy:        4.0.29
- Ant:           Apache Ant(TM) version 1.10.15
- Launcher JVM:  Temurin-21.0.11+10 (build 21.0.11+10-LTS)
- Daemon JVM:    /usr/lib/jvm/java-21-temurin-jdk/bin/java
- kernel:        6.19.14-300.fc44.x86_64

```
### JAVA & JAVAC

```
- Openjdk version "21.0.11" 2026-04-21 LTS
- OpenJDK Runtime Environment Temurin-21.0.11+10 (build 21.0.11+10-LTS)
- OpenJDK 64-Bit Server VM Temurin-21.0.11+10 (build 21.0.11+10-LTS, mixed mode, sharing)
- javac 21.0.11

```

## Features

- Real-time GPS tracking
- Driver behaviour monitoring
- Detailed and summary reports
- Geofencing functionality integration
- Alarms, custom notifications, telegram Bot Command
- Account and device management
- Email and Telegram support command
- Event Forwading & Webhook
- Self Hosted geocoder
- Self Hosted osrm-backend (start v.1.1.0)
- OsrmClient snap to road support route/v1 
- OsrmMatchClient snap to road support match/v1

## Build

```bash

./gradlew clean assemble

```

build into target folder

