## Usage

```gradle
plugins {
    id 'io.github.greymagic27.mc-test-server' version '1.0.0'
}

McTestServer {
    // Optional: specify Minecraft version; leave blank to auto-fetch latest
    serverVersion = "1.20.1"

    // Optional: add extra plugins to download into the test server
    additionalPlugins = [
        [pluginName: "AnotherPlugin.jar", pluginUrl: "https://example.com/AnotherPlugin.jar"],
        [pluginName: "SomeOtherPlugin.jar", pluginUrl: "https://example.com/other.jar"]
    ]
}
```
