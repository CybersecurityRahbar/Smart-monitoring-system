# Android Project Structure

Planned package/module layout:

```text
android/
├── app/
│   └── src/main/java/.../smarttraffic/
│       ├── SmartTrafficApp.kt
│       ├── MainActivity.kt
│       │
│       ├── core/
│       │   ├── model/          # shared domain/data models
│       │   ├── networking/     # HTTP/WebSocket transport abstractions
│       │   ├── storage/        # local persistence
│       │   ├── permissions/    # runtime permissions
│       │   └── ui/             # design system + shared components
│       │
│       ├── domain/
│       │   ├── repository/     # repository contracts
│       │   └── usecase/        # business operations
│       │
│       ├── data/
│       │   ├── device/         # ESP32 data sources/repositories
│       │   ├── evidence/       # image/event persistence
│       │   ├── rules/          # rules and watchlist storage
│       │   └── settings/       # settings persistence
│       │
│       ├── device/
│       │   ├── connection/     # editable connection profiles
│       │   ├── control/        # commands to ESP32-CAM
│       │   └── telemetry/      # status/power/device metrics
│       │
│       ├── vision/
│       │   ├── api/            # Android-facing vision interfaces
│       │   └── native/         # JNI bridge to C++ engine
│       │
│       ├── features/
│       │   ├── dashboard/
│       │   ├── live/
│       │   ├── evidence/
│       │   ├── devices/
│       │   ├── control/
│       │   ├── rules/
│       │   ├── watchlist/
│       │   ├── alerts/
│       │   ├── systemsettings/
│       │   └── appsettings/
│       │
│       └── navigation/
│           └── AppNavHost.kt
│
├── src/main/cpp/               # future C++ vision engine / NDK bridge
└── README.md
```

## Primary navigation philosophy
Use a small set of top-level destinations and keep secondary screens nested. On compact phones this can map to a 4–5 item bottom navigation; on larger screens use a navigation rail/drawer. This follows Android's current adaptive navigation guidance.

## Feature design rule
Each feature owns its screen UI, screen state/event model, and ViewModel. Cross-feature business rules remain in domain/data layers.
