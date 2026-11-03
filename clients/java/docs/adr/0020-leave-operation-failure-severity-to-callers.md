---
status: accepted
---

# Leave operation failure severity to callers

The implemented operation logging puts ordinary request starts and terminal success, failure, or cancellation summaries at DEBUG, including HTTP 4xx/5xx and encoding, network, and decoding failures. The caller receives those failures and decides their application severity; automatically reporting them at WARNING or ERROR would duplicate application reporting and misclassify failures handled normally. ERROR is reserved for failures owned and handled by the SDK with no caller receiving them, and the current client has no such background failure path.

INFO reports successful local client construction, without asserting service reachability or authentication, and preserves the once-only default-await fallback event; WARNING reports the invalid-await-configuration fallback and default base URL selection during configuration build, as refined by [ADR 0023](0023-build-validated-client-configuration.md). `await` does not log propagated failures, wait timeouts, or interruptions again, because it accepts arbitrary futures and cannot reliably identify an underlying client operation.

The core currently emits no ERROR event. This accepted severity policy accompanies the backend boundary in [ADR 0019](0019-use-application-owned-jdk-logging.md) and implemented observation policy in [ADR 0021](0021-observe-operations-without-exposing-content.md). Existing await configuration behavior remains governed by [ADR 0018](0018-bound-waits-without-mutating-futures.md); application examples are in the [README](../../README.md#blocking-helper-and-logging).
