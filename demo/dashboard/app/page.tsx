"use client";

import { useEffect, useState, useCallback } from "react";
import { Box, Flex, Tabs, Text } from "@chakra-ui/react";
import { LuUsers, LuMessageSquare } from "react-icons/lu";

import dynamic from "next/dynamic";

const GraphVisualization = dynamic(
  () => import("@/components/graph-visualization").then((m) => m.GraphVisualization),
  { ssr: false },
);
import { AgentPanel, type AgentStatus } from "@/components/agent-panel";
import { ActivityFeed } from "@/components/activity-feed";
import { HeaderBar } from "@/components/header-bar";
import { NodeDetailDrawer } from "@/components/node-detail-drawer";
import { ChatPanel } from "@/components/chat-panel";

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
  const [highlightIds, setHighlightIds] = useState<Set<string>>(new Set());

  const fetchData = useCallback(async () => {
    try {
      const [graphRes, agentsRes] = await Promise.all([
        fetch("/api/graph"),
        fetch("/api/agents"),
      ]);
      const [graph, agentData] = await Promise.all([
        graphRes.json(),
        agentsRes.json(),
      ]);
      setGraphData(graph);
      if (agentData.agents) setAgents(agentData.agents);
    } catch {
      /* ignore fetch errors */
    }
  }, []);

  useEffect(() => {
    fetchData();
    const interval = setInterval(fetchData, 5000);
    return () => clearInterval(interval);
  }, [fetchData]);

  const handleNodeClick = useCallback((node: GraphNode | null) => {
    if (!node || !node.id) {
      setSelectedNode(null);
    } else {
      setSelectedNode(node);
    }
  }, []);

  const handleNodeDoubleClick = useCallback(
    async (node: GraphNode) => {
      try {
        const res = await fetch("/api/node-detail", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ nodeId: node.id }),
        });
        const data = await res.json();
        const newIds = new Set<string>();
        (data.connections ?? []).forEach((c: { id: string }) => newIds.add(c.id));
        setHighlightIds(newIds);
        setTimeout(() => setHighlightIds(new Set()), 3000);
      } catch {
        /* ignore */
      }
      fetchData();
    },
    [fetchData],
  );

  const handleGraphUpdate = useCallback(() => {
    const prevIds = new Set(graphData.nodes.map((n) => n.id));
    fetchData().then(() => {
      // After refresh, highlight new nodes
    });
  }, [fetchData, graphData.nodes]);

  return (
    <Flex h="100vh" direction="column" bg="bg">
      <HeaderBar
        nodeCount={graphData.nodes.length}
        edgeCount={graphData.edges.length}
        agentCount={agents.length}
        onRefresh={fetchData}
      />

      <Flex flex="1" overflow="hidden">
        {/* Left sidebar */}
        <Box
          w="300px"
          borderRightWidth="1px"
          borderColor="border.subtle"
          bg="bg.panel"
          display="flex"
          flexDirection="column"
          overflow="hidden"
        >
          <Tabs.Root defaultValue="agents" variant="line" size="sm" flex="1" display="flex" flexDirection="column">
            <Tabs.List px="3" pt="2">
              <Tabs.Trigger value="agents">
                <LuUsers />
                <Text ml="1">Agents</Text>
              </Tabs.Trigger>
              <Tabs.Trigger value="chat">
                <LuMessageSquare />
                <Text ml="1">Chat</Text>
              </Tabs.Trigger>
            </Tabs.List>

            <Tabs.Content value="agents" flex="1" overflow="auto" p="0">
              <Box px="3" py="2">
                {agents.map((agent) => (
                  <AgentPanel key={agent.name} agent={agent} />
                ))}
              </Box>
              <ActivityFeed />
            </Tabs.Content>

            <Tabs.Content value="chat" flex="1" overflow="hidden" p="0">
              <ChatPanel agents={agents} onGraphUpdate={handleGraphUpdate} />
            </Tabs.Content>
          </Tabs.Root>
        </Box>

        {/* Main graph area */}
        <Box flex="1" position="relative" bg="bg">
          <GraphVisualization
            nodes={graphData.nodes}
            edges={graphData.edges}
            onNodeClick={handleNodeClick}
            onNodeDoubleClick={handleNodeDoubleClick}
            highlightNodeIds={highlightIds}
          />
        </Box>
      </Flex>

      {/* Node detail drawer */}
      <NodeDetailDrawer
        node={selectedNode}
        onClose={() => setSelectedNode(null)}
      />
    </Flex>
  );
}
