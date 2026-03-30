"use client";

import { useEffect, useState } from "react";

interface ActivityItem {
  id: string;
  label: string;
  type: string;
  agent: string;
  timestamp?: string;
}

const AGENT_COLORS: Record<string, string> = {
  lenny: "#3b82f6",
  scout: "#22c55e",
  forge: "#f97316",
  atlas: "#8b5cf6",
  shared: "#64748b",
};

const TYPE_VERBS: Record<string, string> = {
  Person: "discovered",
  Organization: "identified",
  Entity: "created entity",
  Fact: "recorded fact",
  Message: "sent message",
  Conversation: "started conversation",
  ReasoningTrace: "started reasoning",
  ReasoningStep: "reasoned",
  ToolCall: "called tool",
};

export function ActivityFeed() {
  const [items, setItems] = useState<ActivityItem[]>([]);

  useEffect(() => {
    const fetchActivity = async () => {
      try {
        const res = await fetch("/api/activity");
        if (res.ok) {
          const data = await res.json();
          setItems(data.items ?? []);
        }
      } catch { /* ignore */ }
    };
    fetchActivity();
    const interval = setInterval(fetchActivity, 5000);
    return () => clearInterval(interval);
  }, []);

  return (
    <div style={{ padding: "0 12px" }}>
      <h3 style={{ fontSize: 11, color: "#666", textTransform: "uppercase", letterSpacing: "0.05em", margin: "12px 0 8px" }}>
        Activity Feed
      </h3>
      <div style={{ display: "flex", flexDirection: "column", gap: 2, maxHeight: 300, overflowY: "auto" }}>
        {items.length === 0 && (
          <div style={{ color: "#444", fontSize: 11, padding: 8 }}>No activity yet</div>
        )}
        {items.map((item) => (
          <div key={item.id} style={{
            display: "flex", alignItems: "flex-start", gap: 8, padding: "5px 0",
            borderBottom: "1px solid #1a1a1a", fontSize: 11,
          }}>
            <span style={{
              width: 6, height: 6, borderRadius: "50%", marginTop: 4, flexShrink: 0,
              background: AGENT_COLORS[item.agent] ?? AGENT_COLORS["shared"],
            }} />
            <div style={{ minWidth: 0 }}>
              <span style={{ color: AGENT_COLORS[item.agent] ?? "#666", fontWeight: 600 }}>
                {item.agent}
              </span>
              {" "}
              <span style={{ color: "#888" }}>
                {TYPE_VERBS[item.type] ?? "created"}
              </span>
              {" "}
              <span style={{ color: "#ccc" }}>
                {item.label.length > 30 ? item.label.slice(0, 28) + "..." : item.label}
              </span>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
