"use client";

import { useEffect, useState } from "react";
import { GraphVisualization } from "../components/graph-viz";
import { AgentPanel } from "../components/agent-panel";

interface AgentStatus {
  name: string;
  framework: string;
  language: string;
  color: string;
  entityCount: number;
  traceCount: number;
  messageCount: number;
}

const AGENTS: AgentStatus[] = [
  { name: "Lenny", framework: "PydanticAI", language: "Python", color: "#3b82f6", entityCount: 0, traceCount: 0, messageCount: 0 },
  { name: "Scout", framework: "Vercel AI SDK", language: "TypeScript", color: "#22c55e", entityCount: 0, traceCount: 0, messageCount: 0 },
  { name: "Forge", framework: "Custom HTTP", language: "Go", color: "#f97316", entityCount: 0, traceCount: 0, messageCount: 0 },
  { name: "Atlas", framework: "LangGraph", language: "Python", color: "#8b5cf6", entityCount: 0, traceCount: 0, messageCount: 0 },
];

export default function DashboardPage() {
  const [agents, setAgents] = useState(AGENTS);
  const [graphData, setGraphData] = useState<{ nodes: any[]; edges: any[] }>({ nodes: [], edges: [] });

  useEffect(() => {
    const fetchData = async () => {
      try {
        const res = await fetch("/api/graph");
        if (res.ok) {
          const data = await res.json();
          setGraphData(data);
        }
      } catch {
        // Dashboard gracefully handles missing API
      }

      try {
        const res = await fetch("/api/agents");
        if (res.ok) {
          const data = await res.json();
          if (data.agents) setAgents(data.agents);
        }
      } catch {
        // Use default agent data
      }
    };

    fetchData();
    const interval = setInterval(fetchData, 5000);
    return () => clearInterval(interval);
  }, []);

  return (
    <main style={{ display: "flex", height: "100vh" }}>
      <div style={{ flex: 1, position: "relative" }}>
        <h1 style={{ position: "absolute", top: 16, left: 16, zIndex: 10, fontSize: 20, margin: 0 }}>
          Agent Memory — Polyglot Demo
        </h1>
        <GraphVisualization nodes={graphData.nodes} edges={graphData.edges} />
      </div>
      <aside style={{ width: 320, borderLeft: "1px solid #333", overflow: "auto", padding: 16 }}>
        <h2 style={{ fontSize: 16, marginTop: 0 }}>Agents</h2>
        {agents.map((agent) => (
          <AgentPanel key={agent.name} agent={agent} />
        ))}
      </aside>
    </main>
  );
}
