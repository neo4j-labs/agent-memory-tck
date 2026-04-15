"use client";

import { useEffect, useState, useCallback } from "react";
import { GraphVisualization } from "../components/graph-viz";
import { AgentPanel } from "../components/agent-panel";
import { HeaderBar } from "../components/header-bar";
import { ActivityFeed } from "../components/activity-feed";
import { EntityDetail } from "../components/entity-detail";

interface GraphNode {
  id: string;
  label: string;
  type: string;
  agent?: string;
  properties?: Record<string, unknown>;
}

interface GraphEdge {
  id: string;
  source: string;
  target: string;
  type: string;
}

interface AgentStatus {
  name: string;
  framework: string;
  language: string;
  color: string;
  healthy: boolean;
  entityCount: number;
  traceCount: number;
  messageCount: number;
}

const DEFAULT_AGENTS: AgentStatus[] = [
  { name: "Lenny", framework: "PydanticAI", language: "Python", color: "#3b82f6", healthy: false, entityCount: 0, traceCount: 0, messageCount: 0 },
  { name: "Scout", framework: "Vercel AI SDK", language: "TypeScript", color: "#22c55e", healthy: false, entityCount: 0, traceCount: 0, messageCount: 0 },
  { name: "Forge", framework: "Custom HTTP", language: "Go", color: "#f97316", healthy: false, entityCount: 0, traceCount: 0, messageCount: 0 },
  { name: "Atlas", framework: "LangGraph", language: "Python", color: "#8b5cf6", healthy: false, entityCount: 0, traceCount: 0, messageCount: 0 },
  { name: "Sage", framework: "Semantic Kernel", language: "C#", color: "#ec4899", healthy: false, entityCount: 0, traceCount: 0, messageCount: 0 },
  { name: "Rune", framework: "ellmer", language: "R", color: "#2F9E44", healthy: false, entityCount: 0, traceCount: 0, messageCount: 0 },
];

export default function DashboardPage() {
  const [agents, setAgents] = useState(DEFAULT_AGENTS);
  const [graphData, setGraphData] = useState<{ nodes: GraphNode[]; edges: GraphEdge[] }>({ nodes: [], edges: [] });
  const [selectedNode, setSelectedNode] = useState<GraphNode | null>(null);

  const fetchData = useCallback(async () => {
    try {
      const [graphRes, agentRes] = await Promise.all([
        fetch("/api/graph"),
        fetch("/api/agents"),
      ]);
      if (graphRes.ok) {
        const data = await graphRes.json();
        setGraphData(data);
      }
      if (agentRes.ok) {
        const data = await agentRes.json();
        if (data.agents) setAgents(data.agents);
      }
    } catch { /* graceful degradation */ }
  }, []);

  useEffect(() => {
    fetchData();
    const interval = setInterval(fetchData, 5000);
    return () => clearInterval(interval);
  }, [fetchData]);

  const healthyCount = agents.filter(a => a.healthy).length;

  return (
    <div style={{ display: "flex", flexDirection: "column", height: "100vh" }}>
      <HeaderBar
        nodeCount={graphData.nodes.length}
        edgeCount={graphData.edges.length}
        agentCount={healthyCount}
        onRefresh={fetchData}
      />

      <div style={{ display: "flex", flex: 1, overflow: "hidden" }}>
        {/* Left sidebar — Agents + Activity */}
        <aside style={{
          width: 280, flexShrink: 0, borderRight: "1px solid #222",
          overflow: "auto", background: "#0d0d0d",
        }}>
          <div style={{ padding: "12px 12px 4px" }}>
            <h2 style={{ fontSize: 11, color: "#666", textTransform: "uppercase", letterSpacing: "0.05em", margin: "0 0 8px" }}>
              Agents
            </h2>
            {agents.map((agent) => (
              <AgentPanel key={agent.name} agent={agent} />
            ))}
          </div>

          <div style={{ borderTop: "1px solid #1a1a1a" }}>
            <ActivityFeed />
          </div>
        </aside>

        {/* Center — Graph */}
        <main style={{ flex: 1, position: "relative", overflow: "hidden" }}>
          <GraphVisualization
            nodes={graphData.nodes}
            edges={graphData.edges}
            onNodeClick={(node) => setSelectedNode(node)}
          />
        </main>

        {/* Right sidebar — Entity Detail (slides in on click) */}
        {selectedNode && (
          <EntityDetail
            node={selectedNode}
            onClose={() => setSelectedNode(null)}
          />
        )}
      </div>
    </div>
  );
}
