"use client";

import { useEffect, useState } from "react";

interface NodeData {
  id: string;
  label: string;
  type: string;
  agent?: string;
  properties?: Record<string, unknown>;
}

interface Connection {
  id: string;
  label: string;
  type: string;
  rel: string;
  direction: string;
}

const AGENT_COLORS: Record<string, string> = {
  lenny: "#3b82f6",
  scout: "#22c55e",
  forge: "#f97316",
  atlas: "#8b5cf6",
  shared: "#64748b",
};

const AGENT_LABELS: Record<string, string> = {
  lenny: "Lenny (Python/PydanticAI)",
  scout: "Scout (TypeScript/Vercel AI)",
  forge: "Forge (Go/Custom HTTP)",
  atlas: "Atlas (Python/LangGraph)",
  shared: "Shared (all agents)",
};

export function EntityDetail({ node, onClose }: { node: NodeData; onClose: () => void }) {
  const [connections, setConnections] = useState<Connection[]>([]);

  useEffect(() => {
    const fetchDetail = async () => {
      try {
        const res = await fetch("/api/node-detail", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ nodeId: node.id }),
        });
        if (res.ok) {
          const data = await res.json();
          setConnections(data.connections ?? []);
        }
      } catch { /* ignore */ }
    };
    fetchDetail();
  }, [node.id]);

  const agentColor = AGENT_COLORS[node.agent ?? "shared"] ?? "#666";
  const agentLabel = AGENT_LABELS[node.agent ?? "shared"] ?? node.agent;

  const props = node.properties ?? {};
  const displayProps = Object.entries(props).filter(
    ([k]) => !["embedding", "id", "task_embedding"].includes(k)
  );

  return (
    <div style={{
      width: 350, height: "100%", background: "#111", borderLeft: "1px solid #222",
      overflow: "auto", padding: 16, flexShrink: 0,
    }}>
      {/* Header */}
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start", marginBottom: 16 }}>
        <div>
          <div style={{ fontSize: 16, fontWeight: 700, marginBottom: 4 }}>{node.label}</div>
          <div style={{
            display: "inline-block", fontSize: 10, padding: "2px 8px", borderRadius: 4,
            background: `${agentColor}20`, color: agentColor, border: `1px solid ${agentColor}40`,
          }}>
            {node.type}
          </div>
        </div>
        <button onClick={onClose} style={{
          background: "none", border: "none", color: "#666", cursor: "pointer", fontSize: 18, padding: 4,
        }}>
          &times;
        </button>
      </div>

      {/* Agent attribution */}
      <div style={{ marginBottom: 16, padding: "8px 10px", background: "#0a0a0a", borderRadius: 6, fontSize: 11 }}>
        <span style={{ color: "#666" }}>Created by </span>
        <span style={{ color: agentColor, fontWeight: 600 }}>{agentLabel}</span>
      </div>

      {/* Properties */}
      {displayProps.length > 0 && (
        <div style={{ marginBottom: 16 }}>
          <h4 style={{ fontSize: 11, color: "#666", textTransform: "uppercase", letterSpacing: "0.05em", margin: "0 0 8px" }}>
            Properties
          </h4>
          <div style={{ display: "flex", flexDirection: "column", gap: 4 }}>
            {displayProps.map(([key, value]) => (
              <div key={key} style={{ display: "flex", justifyContent: "space-between", fontSize: 11, padding: "4px 0", borderBottom: "1px solid #1a1a1a" }}>
                <span style={{ color: "#888" }}>{key}</span>
                <span style={{ color: "#ccc", maxWidth: 180, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap", textAlign: "right" }}>
                  {String(value)}
                </span>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Connections */}
      {connections.length > 0 && (
        <div>
          <h4 style={{ fontSize: 11, color: "#666", textTransform: "uppercase", letterSpacing: "0.05em", margin: "0 0 8px" }}>
            Connections ({connections.length})
          </h4>
          <div style={{ display: "flex", flexDirection: "column", gap: 4 }}>
            {connections.map((conn, i) => (
              <div key={i} style={{ fontSize: 11, padding: "6px 8px", background: "#0a0a0a", borderRadius: 4, display: "flex", alignItems: "center", gap: 6 }}>
                <span style={{ color: "#f97316", fontSize: 10 }}>
                  {conn.direction === "out" ? "\u2192" : "\u2190"}
                </span>
                <span style={{ color: "#888" }}>[{conn.rel}]</span>
                <span style={{ color: "#ccc" }}>{conn.label}</span>
                <span style={{ color: "#555", fontSize: 9 }}>({conn.type})</span>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
