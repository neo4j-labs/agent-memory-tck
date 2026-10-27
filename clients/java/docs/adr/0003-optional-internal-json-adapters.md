# Keep JSON adapters internal and mapper dependencies optional

> Refined by [ADR 0025](0025-select-jackson-major-at-runtime.md): a Jackson 2
> adapter now ships beside the Jackson 3 adapter, selection falls back to
> Jackson 2 when Jackson 3 is absent or unusable, and the Jackson 3 floor is
> 3.1.4. Shared codec fixtures and a contract run per codec close the parity
> risk described below.

The Java client ships its JSON adapters inside the single `agent-memory-client` artifact but declares mapper libraries as optional Maven dependencies, so applications retain control of their JSON stack without exposing a JSON SPI in the public API. The adapters implement a narrow, package-private `JsonCodec` that converts between byte arrays and concrete Java types; endpoint code retains responsibility for HTTP semantics, response envelopes, validation, and domain conversion. Public request and value records bind directly where their shape matches hosted camelCase JSON; response envelopes and data needed to construct live handles remain private wire records, avoiding duplicate DTOs for matching domain values. The initial adapter compiles against the Jackson 3.1 LTS line; newer Jackson 3 release lines may be verified as additional runtimes without raising that compatibility floor. A later Jackson 2 adapter can coexist with it, and automatic selection will prefer Jackson 3 when both are present. `MemoryClient.create(...)` fails immediately with a client-owned missing-codec error when no supported mapper is available.

The initial Jackson 3 wire DTOs bind rich Java values such as `UUID`, `MessageRole`, and `Instant` directly. This expedites the first hosted vertical slice but creates a known parity risk for a future Jackson 2 adapter; the binding site must carry a code-level TODO and receive cross-adapter fixture tests before dual-version support is claimed.

The codec ignores unknown response properties so additive NAMS changes remain forward-compatible, while malformed JSON and values incompatible with declared wire-field types still fail decoding. Scalar coercion is disabled: textual numbers, numeric enum values, and other mismatched JSON scalar types are not silently converted into declared wire-field types. General hand-written validation of required decoded values is deferred; a later design may derive that validation from the OpenAPI contract declared by the server. The required conversation-list and message-read arrays are a deliberate exception, recorded in [ADR 0015](0015-distinguish-missing-read-results-from-empty-memory.md).

Codec failures are wrapped in a client-owned `ResponseDecodingException` before completing an asynchronous operation exceptionally. The wrapper identifies the client operation, HTTP status, and content type, includes only a bounded response-body excerpt, retains the mapper failure as its cause, and never captures authorization headers or request bodies.
