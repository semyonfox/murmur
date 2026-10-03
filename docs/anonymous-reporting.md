# Anonymous reporting

Desktop and Android include optional anonymous screen counts and fixed error categories. Reporting is off by default. A build must explicitly enable it and configure an owner HTTPS endpoint ending in `/v1/events`. The user must then opt in through Privacy on desktop or Input on Android.

Desktop build settings are `VITE_MURMUR_TELEMETRY_ENABLED=true` and `VITE_MURMUR_TELEMETRY_ENDPOINT`. Android build properties are `murmurTelemetryEnabled=true` and `murmurTelemetryEndpoint`. No endpoint is selected by default. Endpoint URLs cannot contain credentials, query strings or fragments. Configuring these values is separate from deploying or activating a collector.

Each request contains exactly `version`, `app`, `kind`, `name`, `surface` and `route`. Values come from fixed source categories. No recordings, transcripts, dictionary words, input contents, keys, account identifiers, request URLs, error messages or stacks are included. The local reporting preference stores only a boolean. No visitor identifiers, cookies, persistent event queues or retry queues are created.

Desktop respects Global Privacy Control and Do Not Track as well as the in-app switch. Unreadable privacy settings suppress reporting. Android uses its app preference because native Android provides no browser GPC/DNT signals. Disabled or unconfigured builds send nothing. Reporting failures never block the user action.

Clients allow at most one pending request, 20 attempts per minute and 200 per process/client lifetime. Repeated error categories on the same fixed route are suppressed for one minute. Transport is bounded to two seconds, with no retries or redirects. Android sends through its existing INTERNET permission.

The collector must aggregate counts by UTC day, keep count totals for 30 days and error totals for 14 days, and purge expired totals hourly. It must discard raw events and request metadata, disable access logs, and strip cookies, authorization, referrer, user-agent and forwarded-address headers at its ingestion proxy. Native ingestion requires an explicit originless-client switch. These are deployment requirements, not evidence of an activated collector.

Local regression checks use synthetic transports and test privacy failures, payload fields, budgets, timeout cancellation and continued recovery. Browser fixtures exercise real screen and History error call sites with reporting disabled. Actual screen-reader and physical-device testing remains a separate verification requirement.
