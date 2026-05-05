# SATURN BACKEND OSRM
GPS Tracking Backend

## Overview
```

- Gradle 9.3.1
- Kotlin:        2.2.21
- Groovy:        4.0.29
- Ant:           Apache Ant(TM) version 1.10.15
- Launcher JVM:  21.0.10 (Red Hat, Inc. 21.0.10+7)
- Daemon JVM:    /usr/lib/jvm/java-21-openjdk
- OS:            Linux 6.19.11-200.fc43.x86_64 amd64

```
### JAVA & JAVAC

```

- Openjdk 21.0.11 2026-04-21 LTS
- OpenJDK Runtime Environment (Red_Hat-21.0.11.0.10-1) (build 21.0.11+10-LTS)
- OpenJDK 64-Bit Server VM (Red_Hat-21.0.11.0.10-1) (build 21.0.11+10-LTS, mixed mode, sharing)
- javac 21.0.11

```

## Features

- Real-time GPS tracking
- Driver behaviour monitoring
- Detailed and summary reports
- Geofencing functionality
- Alarms, custom notifications, telegram Bot Command
- Account and device management
- Email and Telegram support
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

