"use client";

interface AgentStatus {
  name: string;
  framework: string;
  language: string;
  color: string;
  entityCount: number;
  traceCount: number;
  messageCount: number;
}

export function AgentPanel({ agent }: { agent: AgentStatus }) {
  return (
    <div
      style={{
        border: `1px solid ${agent.color}40`,
        borderRadius: 8,
        padding: 12,
        marginBottom: 12,
        background: `${agent.color}08`,
      }}
    >
      <div style={{ display: "flex", alignItems: "center", gap: 8, marginBottom: 8 }}>
        <span
          style={{
            width: 10,
            height: 10,
            borderRadius: "50%",
            background: agent.color,
            display: "inline-block",
          }}
        />
        <strong>{agent.name}</strong>
      </div>
      <div style={{ fontSize: 12, color: "#888", marginBottom: 8 }}>
        {agent.language} / {agent.framework}
      </div>
      <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr 1fr", gap: 4, fontSize: 12 }}>
        <div>
          <div style={{ color: "#666" }}>Entities</div>
          <div style={{ fontSize: 18, fontWeight: "bold" }}>{agent.entityCount}</div>
        </div>
        <div>
          <div style={{ color: "#666" }}>Traces</div>
          <div style={{ fontSize: 18, fontWeight: "bold" }}>{agent.traceCount}</div>
        </div>
        <div>
          <div style={{ color: "#666" }}>Messages</div>
          <div style={{ fontSize: 18, fontWeight: "bold" }}>{agent.messageCount}</div>
        </div>
      </div>
    </div>
  );
}
