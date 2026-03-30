"use client";

interface HeaderBarProps {
  nodeCount: number;
  edgeCount: number;
  agentCount: number;
  onRefresh: () => void;
}

export function HeaderBar({ nodeCount, edgeCount, agentCount, onRefresh }: HeaderBarProps) {
  return (
    <header style={{
      height: 48, background: "#111", borderBottom: "1px solid #222",
      display: "flex", alignItems: "center", justifyContent: "space-between",
      padding: "0 16px", flexShrink: 0,
    }}>
      <div style={{ display: "flex", alignItems: "center", gap: 12 }}>
        <span style={{ fontSize: 15, fontWeight: 700, letterSpacing: "-0.02em" }}>
          Agent Memory
        </span>
        <span style={{ fontSize: 11, color: "#666", background: "#1a1a1a", padding: "2px 8px", borderRadius: 4 }}>
          Polyglot Demo
        </span>
      </div>

      <div style={{ display: "flex", alignItems: "center", gap: 16, fontSize: 12 }}>
        <Stat label="Nodes" value={nodeCount} color="#3b82f6" />
        <Stat label="Edges" value={edgeCount} color="#22c55e" />
        <Stat label="Agents" value={agentCount} color="#f97316" />
        <button
          onClick={onRefresh}
          style={{
            background: "#222", border: "1px solid #333", color: "#999",
            padding: "4px 10px", borderRadius: 4, cursor: "pointer", fontSize: 11,
          }}
        >
          Refresh
        </button>
      </div>
    </header>
  );
}

function Stat({ label, value, color }: { label: string; value: number; color: string }) {
  return (
    <div style={{ display: "flex", alignItems: "center", gap: 6 }}>
      <span style={{ color: "#666" }}>{label}</span>
      <span style={{ color, fontWeight: 600, fontVariantNumeric: "tabular-nums" }}>{value}</span>
    </div>
  );
}
