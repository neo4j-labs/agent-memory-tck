"use client";

import { useRef, useMemo, useCallback, useState } from "react";
import { Spinner } from "@chakra-ui/react";
import type NVL from "@neo4j-nvl/base";
import type { Node as NvlNode, Relationship as NvlRel } from "@neo4j-nvl/base";
import { InteractiveNvlWrapper } from "@neo4j-nvl/react";

export interface GraphNode {
  id: string;
  label: string;
  type: string;
  agent?: string;
  properties?: Record<string, unknown>;
}

export interface GraphEdge {
  id: string;
  source: string;
  target: string;
  type: string;
}

interface GraphVisualizationProps {
  nodes: GraphNode[];
  edges: GraphEdge[];
  onNodeClick?: (node: GraphNode | null) => void;
  onNodeDoubleClick?: (node: GraphNode) => void;
  highlightNodeIds?: Set<string>;
}

const AGENT_COLORS: Record<string, string> = {
  lenny: "#3b82f6",
  scout: "#22c55e",
  forge: "#f97316",
  atlas: "#8b5cf6",
  sage: "#ec4899",
  rune: "#2F9E44",
  shared: "#94a3b8",
  unknown: "#64748b",
};

const TYPE_SIZES: Record<string, number> = {
  Person: 28,
  Organization: 30,
  Location: 22,
  Event: 22,
  Entity: 24,
  Conversation: 16,
  Message: 12,
  Fact: 12,
  Preference: 12,
  ReasoningTrace: 20,
  ReasoningStep: 12,
  ToolCall: 10,
  Tool: 10,
};

const TYPE_COLORS: Record<string, string> = {
  Person: "#60a5fa",
  Organization: "#c084fc",
  Location: "#34d399",
  Event: "#fbbf24",
  Fact: "#67e8f9",
  Message: "#a1a1aa",
  ReasoningTrace: "#fb923c",
  ReasoningStep: "#fdba74",
  ToolCall: "#f87171",
  Preference: "#a78bfa",
};

const LEGEND_ITEMS = [
  { label: "lenny", color: AGENT_COLORS.lenny },
  { label: "scout", color: AGENT_COLORS.scout },
  { label: "forge", color: AGENT_COLORS.forge },
  { label: "atlas", color: AGENT_COLORS.atlas },
  { label: "sage", color: AGENT_COLORS.sage },
  { label: "rune", color: AGENT_COLORS.rune },
  { label: "shared", color: AGENT_COLORS.shared },
];

function getNodeColor(node: GraphNode): string {
  if (node.agent && node.agent !== "shared" && AGENT_COLORS[node.agent]) {
    return AGENT_COLORS[node.agent];
  }
  return TYPE_COLORS[node.type] ?? AGENT_COLORS.shared;
}

function truncate(s: string, max: number): string {
  return s.length > max ? s.slice(0, max) + "…" : s;
}

export function GraphVisualization({
  nodes,
  edges,
  onNodeClick,
  onNodeDoubleClick,
  highlightNodeIds,
}: GraphVisualizationProps) {
  const nvlRef = useRef<NVL>(null);
  const nodeDataMap = useRef<Map<string, GraphNode>>(new Map());
  const [ready, setReady] = useState(false);
  const expandedNodes = useRef<Set<string>>(new Set());

  const nvlNodes: NvlNode[] = useMemo(() => {
    const map = new Map<string, GraphNode>();
    const result = nodes.map((node) => {
      map.set(node.id, node);
      const color = getNodeColor(node);
      const size = TYPE_SIZES[node.type] ?? 14;
      const isHighlighted = highlightNodeIds?.has(node.id);
      const isMajor = size >= 20;

      return {
        id: node.id,
        color,
        size,
        activated: isHighlighted,
        captionAlign: "bottom" as const,
        captionSize: isMajor ? 13 : 9,
        captions: [
          { value: truncate(node.label, isMajor ? 30 : 18) },
          ...(isMajor
            ? [{ value: node.type, styles: ["italic"] }]
            : []),
        ],
      };
    });
    nodeDataMap.current = map;
    return result;
  }, [nodes, highlightNodeIds]);

  const nvlRels: NvlRel[] = useMemo(() => {
    const nodeIds = new Set(nodes.map((n) => n.id));
    return edges
      .filter((e) => nodeIds.has(e.source) && nodeIds.has(e.target))
      .map((edge) => ({
        id: edge.id,
        from: edge.source,
        to: edge.target,
        caption: edge.type.replace(/_/g, " "),
        color: "#444",
        width: 1,
        captionSize: 3,
      }));
  }, [edges, nodes]);

  const handleNodeClick = useCallback(
    (node: NvlNode) => {
      const graphNode = nodeDataMap.current.get(node.id);
      if (graphNode && onNodeClick) onNodeClick(graphNode);
    },
    [onNodeClick],
  );

  const handleNodeDoubleClick = useCallback(
    async (node: NvlNode) => {
      const graphNode = nodeDataMap.current.get(node.id);
      if (!graphNode) return;

      if (onNodeDoubleClick) onNodeDoubleClick(graphNode);

      if (expandedNodes.current.has(node.id)) return;
      expandedNodes.current.add(node.id);

      try {
        const res = await fetch("/api/node-detail", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ nodeId: node.id, expand: true }),
        });
        const data = await res.json();
        const connections = data.connections ?? [];

        const existingNodeIds = new Set(
          nvlRef.current?.getNodes().map((n: NvlNode) => n.id) ?? [],
        );
        const existingRelIds = new Set(
          nvlRef.current?.getRelationships().map((r: NvlRel) => r.id) ?? [],
        );

        const newNodes: NvlNode[] = [];
        const newRels: NvlRel[] = [];

        for (const conn of connections) {
          if (!conn.id || existingNodeIds.has(conn.id)) continue;

          const color =
            conn.agent && AGENT_COLORS[conn.agent]
              ? AGENT_COLORS[conn.agent]
              : TYPE_COLORS[conn.type] ?? AGENT_COLORS.shared;
          const size = TYPE_SIZES[conn.type] ?? 14;

          newNodes.push({
            id: conn.id,
            color,
            size,
            captionAlign: "bottom",
            captionSize: size >= 20 ? 13 : 9,
            captions: [{ value: truncate(conn.label, 25) }],
          });

          nodeDataMap.current.set(conn.id, {
            id: conn.id,
            label: conn.label,
            type: conn.type,
            agent: conn.agent,
            properties: conn.properties,
          });

          const relId = `${node.id}-${conn.rel}-${conn.id}`;
          if (!existingRelIds.has(relId)) {
            newRels.push({
              id: relId,
              from: conn.direction === "out" ? node.id : conn.id,
              to: conn.direction === "out" ? conn.id : node.id,
              caption: conn.rel.replace(/_/g, " "),
              color: "#555",
              width: 1,
              captionSize: 7,
            });
          }
        }

        if (newNodes.length > 0 || newRels.length > 0) {
          nvlRef.current?.addAndUpdateElementsInGraph(newNodes, newRels);

          setTimeout(() => {
            const fitIds = [node.id, ...newNodes.map((n) => n.id)];
            nvlRef.current?.fit(fitIds, { animated: true });
          }, 300);
        }
      } catch {
        expandedNodes.current.delete(node.id);
      }
    },
    [onNodeDoubleClick],
  );

  const handleCanvasClick = useCallback(() => {
    if (onNodeClick) onNodeClick(null);
  }, [onNodeClick]);

  if (nodes.length === 0) {
    return (
      <div
        style={{
          width: "100%",
          height: "100%",
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
          flexDirection: "column",
          gap: 12,
        }}
      >
        <Spinner size="lg" color="blue.fg" />
        <span style={{ color: "#888", fontSize: 14 }}>
          Waiting for agents to create entities…
        </span>
        <span style={{ color: "#555", fontSize: 12 }}>
          Run: uv run python demo/seed-data.py
        </span>
      </div>
    );
  }

  return (
    <div style={{ position: "relative", width: "100%", height: "100%" }}>
      <InteractiveNvlWrapper
        ref={nvlRef}
        nodes={nvlNodes}
        rels={nvlRels}
        layout="forceDirected"
        nvlOptions={{
          initialZoom: 1,
          minZoom: 0.05,
          maxZoom: 5,
          renderer: "canvas",
          allowDynamicMinZoom: true,
          styling: {
            defaultNodeColor: "#94a3b8",
            defaultRelationshipColor: "#555",
          },
        }}
        nvlCallbacks={{
          onLayoutDone: () => {
            setReady(true);
            nvlRef.current?.fit?.(
              nvlNodes.map((n) => n.id),
              { animated: true },
            );
          },
        }}
        mouseEventCallbacks={{
          onZoom: true,
          onPan: true,
          onDrag: true,
          onHover: true,
          onNodeClick: handleNodeClick,
          onNodeDoubleClick: handleNodeDoubleClick,
          onCanvasClick: handleCanvasClick,
        }}
        interactionOptions={{
          selectOnClick: true,
          drawShadowOnHover: true,
        }}
        style={{ width: "100%", height: "100%" }}
      />

      {/* Legend */}
      <div
        style={{
          position: "absolute",
          bottom: 12,
          left: 12,
          background: "rgba(0,0,0,0.7)",
          backdropFilter: "blur(8px)",
          padding: "6px 12px",
          borderRadius: 8,
          display: "flex",
          alignItems: "center",
          gap: 12,
          pointerEvents: "none",
          zIndex: 10,
        }}
      >
        <span style={{ fontSize: 11, color: "rgba(255,255,255,0.6)" }}>
          {nodes.length} nodes · {edges.length} edges
        </span>
        <span
          style={{
            width: 1,
            height: 12,
            background: "rgba(255,255,255,0.2)",
          }}
        />
        {LEGEND_ITEMS.map((item) => (
          <span
            key={item.label}
            style={{ display: "flex", alignItems: "center", gap: 4 }}
          >
            <span
              style={{
                width: 8,
                height: 8,
                borderRadius: "50%",
                background: item.color,
                boxShadow: `0 0 4px ${item.color}`,
              }}
            />
            <span style={{ fontSize: 10, color: "rgba(255,255,255,0.6)" }}>
              {item.label}
            </span>
          </span>
        ))}
      </div>
    </div>
  );
}
