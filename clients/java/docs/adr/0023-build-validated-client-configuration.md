---
status: accepted
---

# Build validated client configuration

> Amended by [ADR 0024](0024-reuse-application-http-clients.md): the builder
> also accepts an application HTTP client through `jdkHttpClient`,
> `restClient`, `webClient`, or `langChain4jHttpClient`, or a custom transport through
> `httpTransport`. The last setter called wins.
> The built configuration holds a reference to that client and does not
> copy it; the other settings are still immutable.

MemoryClient exposes only create() and create(MemoryClientConfiguration); applications supply immutable settings built with MemoryClientConfiguration.builder() rather than key-only or URL/key factory arguments. The no-argument factory builds an empty configuration builder, keeping environment resolution and validation in build() so callers can validate settings before selecting the default HTTP adapter. Omitted settings read MEMORY_API_KEY and MEMORY_ENDPOINT at build time; the key is required, and an absent or blank environment URL uses https://memory.neo4jlabs.com/v1. Explicit settings override the environment. Setters reject null immediately using Objects.requireNonNull, so a null field unambiguously means no override and needs no separate set flag. Explicit blank values fail validation in build() rather than silently changing their source. Each successful build that selects the default URL emits a WARNING identifying MEMORY_ENDPOINT and the default URL through org.neo4j.agentmemory.MemoryClientConfiguration.

Base URLs are trimmed, absolute HTTP(S) URLs with a host, and cannot contain user info, query parameters, or fragments. A trailing slash is supported when joining operation paths. The immutable configuration uses a final class with a private constructor instead of a record so validation cannot be bypassed and generated text does not expose the API key. Authentication continues to require a Workspace API key; an Admin key with an explicit workspace ID remains unsupported.

Initialization logs the configured baseUrl at INFO to identify the selected service before any remote request. The configuration fallback warning and initialization event deliberately refine ADR 0021's URL exclusion: operation events continue to exclude URLs, and all events continue to exclude credentials and payloads. URL validation rejects credential-bearing user info and query parameters; client construction still verifies neither reachability nor authentication. Logging remains application-owned as established by ADR 0019.
