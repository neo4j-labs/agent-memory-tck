/**
 * Deterministic test data for TCK conformance tests.
 *
 * These constants mirror tck/fixtures/data.py exactly to ensure
 * cross-language test consistency.
 */

export const SESSION_A = "tck-session-alpha";
export const SESSION_B = "tck-session-beta";
export const SESSION_C = "tck-session-gamma";

export const LONG_CONTENT = "x".repeat(10_000);
export const UNICODE_CONTENT = "Hello \u4e16\u754c \ud83c\udf0d \u00e9\u00e8\u00ea \u00fc\u00f6\u00e4 \u2603\ufe0f \u2764\ufe0f\u200d\ud83d\udd25";
export const SPECIAL_CHARS_CONTENT = 'Line1\nLine2\tTabbed "quoted" \'single\' back\\slash';
export const EMPTY_CONTENT = "";

export const NESTED_METADATA = {
  source: "test",
  priority: "high",
  tags: ["memory", "tck", "bronze"],
  nested: { level2: { level3: "deep_value" } },
  count: 42,
  active: true,
};

export const ENTITIES = [
  { name: "Alice Johnson", type: "PERSON", description: "Software engineer at Acme Corp" },
  { name: "Bob Smith", type: "PERSON", description: "Project manager" },
  { name: "Acme Corp", type: "ORGANIZATION", description: "Technology company" },
  { name: "San Francisco", type: "LOCATION", description: "City in California" },
  { name: "Product Launch", type: "EVENT", description: "Annual product launch event" },
];

export const PREFERENCES = [
  { category: "language", preference: "Prefers Python over JavaScript", context: "programming" },
  { category: "communication", preference: "Prefers async communication", context: "work" },
  { category: "food", preference: "Vegetarian diet", context: "dietary restrictions" },
];

export const FACTS = [
  { subject: "Alice Johnson", predicate: "WORKS_AT", object: "Acme Corp" },
  { subject: "Acme Corp", predicate: "LOCATED_IN", object: "San Francisco" },
  { subject: "Bob Smith", predicate: "MANAGES", object: "Product Launch" },
];

export const CONVERSATION_MESSAGES = [
  { role: "user" as const, content: "Hello, I'm working on the agent memory project." },
  { role: "assistant" as const, content: "I can help with the agent memory project. What do you need?" },
  { role: "user" as const, content: "I need to add entity extraction for people and organizations." },
  { role: "assistant" as const, content: "Entity extraction can identify people like Alice and organizations like Acme Corp from text." },
  { role: "user" as const, content: "Great, let's start with the Person entity type." },
];

export const TRACE_TASK = "Find information about Alice Johnson's role at Acme Corp";
