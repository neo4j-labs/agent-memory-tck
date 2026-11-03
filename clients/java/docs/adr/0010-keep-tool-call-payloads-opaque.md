# Keep tool-call payloads opaque

> Refined by [ADR 0012](0012-store-nullable-domain-state-directly.md): opaque tool-call output remains a `String`, but nullable state is stored directly and exposed through a separate `Optional` view.

The Java client represents tool-call input as a `String` and optional output as an `Optional<String>`, preserving the exact payload supplied by agent frameworks without parsing or validating it as JSON. Spring AI exposes both assistant tool arguments and tool response data as strings, and callers using other frameworks can supply JSON text or plain text without introducing JSON-tree types into the public domain model.
