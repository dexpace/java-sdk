# Per-API Spring Boot starter

Status: design spec. Closes #69.

> Design note for the **generator**. We do **not** build a toolkit-level starter now. The toolkit
> (`sdk-core` and friends) stays framework-agnostic; Spring is a generated-output concern. Spring
> dependencies are confined to the **generated starter module** and never reach any toolkit module.
> Prior art: openai-java ships a per-SDK `spring-boot-starter` module of exactly this shape.

Related: #60 (`withOptions` / unified client config) — the customizer hook below is the Spring-facing
face of that unified config.

## Problem

Spring Boot users expect to add one starter dependency, set a couple of properties, and get an
autoconfigured, injectable client — without writing the wiring that installs an `IoProvider`, picks a
transport, and assembles a pipeline by hand. A generated SDK that offers no autoconfiguration forces
every Spring consumer to re-derive the same bean graph.

The generator therefore emits, **per generated API**, a small starter module with three pieces:

1. a `@ConfigurationProperties` class binding `application.yml` properties,
2. a `fun interface` client customizer so users can adjust the client before it is built, and
3. an `@AutoConfiguration` that assembles the client bean, backing off if the user already defined
   one.

## The bean it assembles

The autoconfiguration's job is to stand up the same object graph a manual user would build, from
`sdk-core` primitives:

- **`IoProvider`** — installed once via `Io.installProvider(...)` (the `sdk-io-okio3`
  `OkioIoProvider` by default). `Io.installProvider` is idempotent for the same instance and throws
  on a conflicting double-install, so the autoconfiguration installs exactly once at bean-creation
  time.
- **Transport** — an `HttpClient` (or `AsyncHttpClient`) from a transport module
  (`sdk-transport-okhttp` / `sdk-transport-jdkhttp`). The starter owns the one it creates and lets
  the transport's own `close()` release it; a user-supplied client is never closed by the starter
  (the transport SPIs already encode this ownership rule).
- **`HttpPipeline`** — built with `HttpPipelineBuilder(httpClient)`, wiring the standard pillar
  stages (retry / auth / logging / serde) from the bound properties.

The generated top-level client wraps that pipeline. The customizer (below) runs against the builder
just before `build()`, so users can append steps or swap a pillar.

## Properties

```kotlin
// GENERATED — illustrative target output for the "Example" API; not compiled here.
@ConfigurationProperties(prefix = "dexpace.example")
public data class ExampleClientProperties(
    /** Base URL of the API. */
    var baseUrl: String = "https://api.example.com",
    /** Bearer / API-key credential; bound from config or an env var. */
    var apiKey: String? = null,
    /** Per-request timeout. */
    var timeout: Duration = Duration.ofSeconds(30),
    /** Max retry attempts for the retry pillar. */
    var maxRetries: Int = 2,
)
```

Bound from, e.g.:

```yaml
dexpace:
  example:
    base-url: https://api.example.com
    api-key: ${EXAMPLE_API_KEY}
    timeout: 30s
    max-retries: 2
```

Property names intentionally echo the toolkit's existing `Configuration` keys (e.g.
`MAX_RETRY_ATTEMPTS`, `LOG_LEVEL`) so the Spring binding and the plain-`Configuration` path stay
recognizably the same knobs.

## Customizer

A `fun interface` (single-abstract-method, so it is a clean Java/Kotlin lambda target) that receives
the `HttpPipelineBuilder` before the pipeline is built:

```kotlin
// GENERATED — illustrative target output; not compiled here.
public fun interface ExampleClientCustomizer {
    /** Adjust the pipeline builder before the client is assembled. */
    public fun customize(builder: HttpPipelineBuilder)
}
```

This is the extension seam: a user defines a `@Bean ExampleClientCustomizer { builder -> ... }` to
append an `HttpStep`, replace a pillar (`builder.replace<...>()`), or attach instrumentation, without
forking the autoconfiguration. It is the Spring-facing surface of the unified client config tracked
in #60 — Spring users reach the same configuration through a bean instead of a builder call.

## Autoconfiguration

```kotlin
// GENERATED — illustrative target output; not compiled here.
@AutoConfiguration
@EnableConfigurationProperties(ExampleClientProperties::class)
public class ExampleClientAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public fun exampleClient(
        properties: ExampleClientProperties,
        customizers: ObjectProvider<ExampleClientCustomizer>,
    ): ExampleClient {
        Io.installProvider(OkioIoProvider)                       // idempotent; once per JVM

        val transport: HttpClient = OkHttpHttpClient.builder()   // starter-owned; closed by transport
            .baseUrl(properties.baseUrl)
            .callTimeout(properties.timeout)
            .build()

        val builder = HttpPipelineBuilder(transport)
            .append(/* auth pillar from properties.apiKey */)
            .append(/* retry pillar from properties.maxRetries */)
            .append(/* logging + serde pillars */)

        customizers.orderedStream().forEach { it.customize(builder) }

        return ExampleClient(builder.build())
    }
}
```

`@ConditionalOnMissingBean` is the key decision: if the user has already declared their own
`ExampleClient` bean (fully manual wiring), the starter backs off entirely. The starter provides a
default, never a mandate. Registration is via the Spring Boot 3
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` file in the
generated starter module.

## Module layout

The starter is its **own generated module**, e.g. `example-sdk-spring-boot-starter`, depending on the
generated `example-sdk`, a transport module, an I/O module, and `spring-boot-autoconfigure`. The
Spring dependency stops there. The core generated SDK (`example-sdk`) has **no** Spring dependency, so
non-Spring users consume it untouched — mirroring openai-java's split between its core SDK and its
separate `spring-boot-starter` artifact.

## Decisions / trade-offs

- **`@ConditionalOnMissingBean` over an unconditional bean** — autoconfiguration must yield to
  explicit user wiring; otherwise advanced users cannot fully control the client.
- **Customizer as a `fun interface`, not subclassing** — a SAM lambda is the lightest extension
  point and composes (multiple customizer beans run in order) without inheritance.
- **Starter assembles `{IoProvider + transport + HttpPipeline}` only** — it does not invent new
  runtime behavior; it is pure wiring over existing `sdk-core` / adapter primitives, so the toolkit
  stays the single source of behavior and Spring stays a thin assembly layer.
- **Spring confined to the starter module** — keeps the generated core SDK dependency-light and
  usable outside Spring, consistent with the toolkit's framework-agnostic stance.

## Acceptance mapping

- *Starter shape recorded for codegen* — `@ConfigurationProperties` class, `fun interface`
  customizer, and `@AutoConfiguration` with `@Bean @ConditionalOnMissingBean` assembling
  `{IoProvider + transport + HttpPipeline}`, all in a per-API generated starter module with Spring
  deps confined there.
