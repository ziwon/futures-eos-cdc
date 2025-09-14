rootProject.name = "trading-signals"

include(
    "libs:common-model",
    "libs:common-kafka",
    "apps:signal-generator",
    "apps:signal-processor",
    "apps:order-manager"
)

