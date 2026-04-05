## Usage

```gradle
plugins {
    id 'io.github.greymagic27.mc-test-server' version '1.0.0'
}

McTestServer {
    serverVersion = "1.20.1"
    additionalPlugin("AnotherPlugin.jar", "https://example.com/AnotherPlugin.jar")
    additionalPlugin("SomeOtherPlugin.jar", "https://example.com/other.jar")
}
```
