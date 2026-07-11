# jms-spy

## Commands

Build and run (Maven wrapper is not present; use system `mvn`):

- Build: `mvn clean package`
- Run the JavaFX app: `mvn spring-boot:run`
- Run all tests: `mvn test`
- Run a single test class: `mvn test -Dtest=MainApplicationTests`
- Run a single test method: `mvn test -Dtest=MainApplicationTests#givenApplicationContextIsLoadedThenJmsConnectionServiceShouldNotBeNull`

Package a standalone Windows executable (requires JDK 14+ for `jpackage`, run from repo root in PowerShell):
```
.\jpackage.ps1
```
This produces `target\dist\JmsSpy\JmsSpy.exe` (no console) and `target\dist\JmsSpy\JmsSpyConsole.exe` (with console). Both bundle their own JRE. The script builds the jar, copies runtime dependencies (dropping Lombok, which is compile-time only) into `target\jpackage-input`, and invokes `jpackage` with two launchers sharing one app image.

## Usage

1. **Connect** — enter the broker URL, and optionally a username/password, then click **Connect**. On first run (no `~/.jms-spy/config.properties` yet), these three fields default to the `SPRING_ARTEMIS_BROKER_URL`/`SPRING_ARTEMIS_USER`/`SPRING_ARTEMIS_PASSWORD` env vars if set — the same ones `item-server`/`employee-server` use (see the root repo's `docker-compose.yml`/Helm charts) — and are left blank otherwise.
2. Pick the **Subscribe** or **Publish** tab:
   - **Subscribe** — enter a destination name, choose **Queue** or **Topic**, then click **Listen** to start consuming. Incoming messages are shown in the text area, either appended (default) or replacing the previous message, per the radio toggle.
   - **Publish** — enter a destination name and type, type a message body in the text area, then click **Publish** to send it as a `TextMessage`. Useful for triggering test events without going through `item-server`/`employee-server`'s REST APIs.
   - Both tabs share the same connection but have independent destination name/type fields, so you can e.g. subscribe to one destination while publishing test messages to another.
   - `item-server` and `employee-server` both publish to plain JMS **queues** (`item-events`, `employee-events` — see each server's `app.jms.*` property), so leave **Queue** selected to interoperate with either one.
   - Picking the wrong type doesn't error — it just silently doesn't connect the two sides, since Artemis routes queue-sent messages only to queue consumers and topic-sent messages only to topic consumers, even on the same destination name.
3. **Dark Mode** — the checkbox in the top-right corner swaps between `light-theme.css` and `dark-theme.css`, applied to the whole window immediately.
4. Broker URL, username, both tabs' destination name/type, append/replace choice, and dark mode are remembered between runs (see `UserPreferencesStore` below); the password is never saved and must be re-entered each session.

## Architecture

This is a Spring Boot + JavaFX desktop app (Java 21) that connects to an Apache ActiveMQ Artemis broker and displays messages received on a JMS queue or topic. All source lives under the single package `com.example.jfx.spring.jms`.

Spring Boot manages the application context; JavaFX drives the UI. They're bridged in [JavaFxApplication.java](src/main/java/com/example/jfx/spring/jms/JavaFxApplication.java):
- `MainApplication.main` calls `Application.launch(JavaFxApplication.class, ...)` — JavaFX owns the entry point, not `SpringApplication.run`.
- `JavaFxApplication.init()` boots a Spring context (`WebApplicationType.NONE`) and registers the JavaFX `Application`, `Parameters`, and `HostServices` as beans so Spring-managed components can depend on them.
- `JavaFxApplication.start(Stage)` doesn't build UI directly — it publishes a `StageReadyEvent` (an inner class of `JavaFxApplication`) through the Spring context once the primary `Stage` is available.
- `PrimaryStageInitializer` listens for `StageReadyEvent`, loads `primary.fxml` (the single, only screen), and sets it as the primary `Stage`'s scene. `FXMLLoader.setControllerFactory` is wired to `applicationContext::getBean`, so `PrimaryController` is constructed via Spring DI.

JMS connectivity lives in [JmsConnectionService.java](src/main/java/com/example/jfx/spring/jms/JmsConnectionService.java): a `@Component` that owns one `Connection`/`Session`/`MessageConsumer` at a time, built directly against the Artemis client API (`org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory`, `jakarta.jms.*`) rather than Spring's JMS auto-configuration — the broker URL, credentials, destination name, and destination type ([DestinationType.java](src/main/java/com/example/jfx/spring/jms/DestinationType.java): `QUEUE` or `TOPIC`) are all supplied at runtime from the UI, not fixed at startup. `connect()`/`disconnect()` and `listen()`/`stopListening()` are independent: you must be connected before you can listen, and reconnecting tears down any active consumer first. `@PreDestroy` ensures the connection is closed when the Spring context shuts down. `publish(destinationName, destinationType, body)` is stateless by comparison — it opens a `MessageProducer` in a try-with-resources block, sends one `TextMessage`, and closes it, since there's no ongoing publish session to track the way `listen`/`stopListening` track a consumer.

`primary.fxml`'s root has a `TabPane` with two tabs, **Subscribe** (the original destination/Listen/append-replace/message-area controls) and **Publish** (its own destination name/type fields, a message-body `TextArea`, and a Publish button/status label) — both call into the same `JmsConnectionService`/`Connection`, so a single Connect covers both tabs.

[PrimaryController.java](src/main/java/com/example/jfx/spring/jms/PrimaryController.java) wires the UI to `JmsConnectionService`: the Connect/Listen buttons toggle their own state by asking the service `isConnected()`/`isListening()` rather than tracking duplicate state; the Publish button is simply enabled/disabled alongside Listen based on connection state, since publishing doesn't have its own persistent "is publishing" state to track. Incoming messages arrive on a JMS provider thread, so `onMessageReceived` always hands off to `Platform.runLater` before touching the `TextArea`; the append-vs-replace radio buttons decide whether that update appends or calls `setText`.

The Dark Mode checkbox drives `PrimaryController.applyTheme(boolean)`, which swaps `rootPane.getStylesheets()` between `/light-theme.css` and `/dark-theme.css` (both plain CSS files under `src/main/resources`, overriding JavaFX's default Modena `-fx-base`/`-fx-background`/`-fx-text-fill`/`-fx-accent`). `rootPane` (the top-level `VBox`, given an `fx:id` in `primary.fxml`) is used instead of the `Scene`'s own stylesheet list because at `initialize()` time — where the saved theme preference needs to be applied — the FXML's root node exists but hasn't been attached to a `Scene` yet (`PrimaryStageInitializer` creates the `Scene` only after `FXMLLoader.load()` returns); `Parent.getStylesheets()` works regardless of scene attachment, so it doesn't matter that it's set before a `Scene` exists.

User choices (broker URL, username, subscribe destination name/type, append/replace mode, dark mode, publish destination name/type) are persisted by [UserPreferencesStore.java](src/main/java/com/example/jfx/spring/jms/UserPreferencesStore.java) to `~/.jms-spy/config.properties` (a plain `java.util.Properties` file, not the classpath `application.properties`) and reloaded into the form on `PrimaryController.initialize()`. The password field is intentionally never persisted — it must be re-entered each session. The message body typed into the Publish tab is not persisted either — only its destination name/type are. Preferences are saved on successful connect/listen/publish and whenever the destination-type, display-mode, or dark-mode toggles change.

When no config file exists yet — first run, or a fresh machine — `JmsSpyPreferences.defaults()` and `PrimaryController` fall back to the `SPRING_ARTEMIS_BROKER_URL`/`SPRING_ARTEMIS_USER`/`SPRING_ARTEMIS_PASSWORD` env vars (blank if unset) for broker URL, username, and password respectively, rather than a hardcoded broker URL. This mirrors the env var names `item-server`/`employee-server` already read for their own Artemis connections, so pointing jms-spy at the same broker (e.g. the KinD cluster's TCP passthrough on `localhost:61616`) needs no retyping if those vars are already exported in the shell. `UserPreferencesStore.hasSavedConfig()` is what gates this — once a config file is saved, its own `brokerUrl`/`username` values take over and the env vars stop applying (password is still never read from the file, but also isn't re-defaulted from the env once a config file exists).

Static UI configuration (window title/size, initial FXML view) is typed via `AppProperties` ([AppProperties.java](src/main/java/com/example/jfx/spring/jms/AppProperties.java)), a `@ConfigurationProperties("app")` record bound from `application.properties`. `@ConfigurationPropertiesScan` on `MainApplication` discovers it automatically — no explicit `@EnableConfigurationProperties` needed. Don't confuse this with `UserPreferencesStore`'s external config file — `AppProperties` is fixed at build time, the preferences file is mutable at runtime.

Lombok is used for boilerplate (`@Slf4j`, `@RequiredArgsConstructor`, `@SneakyThrows`, `val`) but is excluded from the packaged app in both the Spring Boot Maven plugin config (`pom.xml`) and `jpackage.ps1`, since it's compile-time only.

The `artemis-jakarta-client-all` dependency version is pinned explicitly via the `artemis.version` property — Spring Boot 3.2.0's parent BOM does not manage this artifact's version.
