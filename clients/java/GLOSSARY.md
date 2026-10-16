# Agent Memory

The shared language for memory capabilities exercised by the Agent Memory TCK and its language clients.

## Language

**Conversation**:
A remotely persisted container with a stable identity that scopes the ordered messages, derived memory, and reasoning records belonging to one ongoing interaction with an agent. It may optionally be associated with a known user when created.
_Avoid_: Session

**Conversation Context**:
A structured view of a conversation's reflections, observations, and recent messages, suitable for supplying memory to an agent. All three collections are present but may be empty.

**Entity**:
A named piece of knowledge discovered or stored in the knowledge graph. Its type is an open, service-provided classification rather than a fixed client vocabulary.

**User Memory**:
Everything stored for one known user: the Conversations created for them, with everything those Conversations own, and the Entities in that user's memory. Shared Entities extracted from their Conversations are not part of it.

**Exchange**:
An ordered user message and assistant response belonging to the same conversational turn. Each message in an exchange is persisted exactly once, even when the first exchange is anchored through separate writes.
_Avoid_: Turn

**Reasoning Step**:
A persisted account of reasoning, action, and an optional result within a Conversation. It has a stable identity and groups the Tool Calls made while carrying out that action.

**Reasoning**:
Application-supplied trace content describing the thought process associated with a Reasoning Step. The core memory client treats it as opaque content and does not obtain it directly from a model.

**Tool Call**:
A recorded invocation of a tool within a Reasoning Step, including opaque string input, optional opaque string output, completion status, and optional duration.

**Reasoning Trace**:
The structured history of Reasoning Steps and their Tool Calls belonging to a Conversation.

**Reasoning Step Explanation**:
A detailed view of a Reasoning Step containing its recorded Tool Calls and the Entities it influenced.
_Avoid_: Decision Explanation
