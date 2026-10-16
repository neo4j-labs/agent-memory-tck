# Java Conformance Server

The vocabulary of the TCK bridge protocol as the Java conformance server speaks it. It is kept apart from the [Agent Memory client language](../GLOSSARY.md); where a term here relies on a client concept, it names that concept explicitly.

## Language

**Bridge Method**:
An operation of the TCK bridge protocol, named after the TCK adapter method it carries, such as `add_message`.
_Avoid_: Endpoint, route

**Session**:
A caller-chosen name under which the TCK groups messages. It is bound to at most one Conversation at a time; clearing a Session ends that binding, and its next message starts a new Conversation.
_Avoid_: Conversation (a Session is a name, not the persisted container it is bound to)

**Trace**:
A TCK unit of reasoning with its own identity, started for one task within a Session, built from steps, and completed with an outcome.
_Avoid_: Reasoning Trace (the client's history of one Conversation's Reasoning Steps)

## Progress

**Ledger**:
The reviewed record of which TCK scenarios the Java client is expected to pass, and which are blocked by a Client Gap or a Service Gap.

**Client Gap**:
A Bridge Method the hosted service can honour but the Java client, through the conformance server, does not yet carry. Closing it is planned client or translation work.

**Service Gap**:
A Bridge Method with no hosted equivalent. The Java client cannot close it; it lifts only when the service changes.
_Avoid_: Unsupported (it hides whether the client or the service is missing)
