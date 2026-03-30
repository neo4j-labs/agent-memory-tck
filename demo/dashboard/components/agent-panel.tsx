"use client";

interface AgentStatus {
  name: string;
  framework: string;
  language: string;
  color: string;
  healthy?: boolean;
  entityCount: number;
  traceCount: number;
  messageCount: number;
}

export function AgentPanel({ agent }: { agent: AgentStatus }) {
  const langBadgeColors: Record<string, string> = {
    Python: "#306998",
    TypeScript: "#3178c6",
    Go: "#00ADD8",
  };
  const badgeColor = langBadgeColors[agent.language] ?? "#666";

  return (
    <div style={{
      border: `1px solid ${agent.color}30`,
      borderRadius: 8,
      padding: 12,
      marginBottom: 8,
      background: `${agent.color}06`,
    }}>
      {/* Header */}
      <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", marginBottom: 6 }}>
        <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
          <span style={{
            width: 8, height: 8, borderRadius: "50%",
            background: agent.healthy ? "#22c55e" : "#ef4444",
            display: "inline-block",
            boxShadow: agent.healthy ? "0 0 6px #22c55e80" : "none",
          }} />
          <strong style={{ fontSize: 13 }}>{agent.name}</strong>
        </div>
        <span style={{
          fontSize: 9, padding: "1px 6px", borderRadius: 3,
          background: `${badgeColor}20`, color: badgeColor,
          border: `1px solid ${badgeColor}40`,
        }}>
          {agent.language}
        </span>
      </div>

      {/* Framework */}
      <div style={{ fontSize: 10, color: "#666", marginBottom: 8 }}>
        {agent.framework}
      </div>

      {/* Stats */}
      <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr 1fr", gap: 4, fontSize: 11 }}>
        <StatBox label="Entities" value={agent.entityCount} />
        <StatBox label="Traces" value={agent.traceCount} />
        <StatBox label="Messages" value={agent.messageCount} />
      </div>
    </div>
  );
}

function StatBox({ label, value }: { label: string; value: number }) {
  return (
    <div style={{ textAlign: "center" }}>
      <div style={{ fontSize: 16, fontWeight: 700, fontVariantNumeric: "tabular-nums", color: value > 0 ? "#e2e8f0" : "#444" }}>
        {value}
      </div>
      <div style={{ fontSize: 9, color: "#555" }}>{label}</div>
    </div>
  );
}
