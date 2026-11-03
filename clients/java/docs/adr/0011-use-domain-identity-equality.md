# Use UUID identity equality for identified domain objects

Server-created `Conversation`, `ReasoningStep`, `Entity`, `Message`, `ToolCall`, `Observation`, and `Reflection` objects compare equal when they have the same concrete domain type and UUID, regardless of whether their immutable snapshots contain different non-identity fields. Request objects and aggregate snapshots retain structural equality. This makes repeated reads and enriched live handles behave as the same domain identity without relying on Java reference identity.
