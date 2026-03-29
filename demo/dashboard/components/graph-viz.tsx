"use client";

/**
 * Graph visualization component using NVL (Neo4j Visualization Library).
 *
 * Renders the shared knowledge graph with nodes color-coded by the agent
 * that created them:
 *   - Lenny (Python/PydanticAI) = Blue
 *   - Scout (TypeScript/Vercel AI) = Green
 *   - Forge (Go/Custom) = Orange
 *   - Atlas (Python/LangGraph) = Purple
 */

import { useEffect, useRef } from "react";

interface GraphNode {
  id: string;
  label: string;
  type: string;
  agent?: string;
}

interface GraphEdge {
  id: string;
  source: string;
  target: string;
  type: string;
}

interface GraphVisualizationProps {
  nodes: GraphNode[];
  edges: GraphEdge[];
}

const AGENT_COLORS: Record<string, string> = {
  lenny: "#3b82f6",
  scout: "#22c55e",
  forge: "#f97316",
  atlas: "#8b5cf6",
  default: "#6b7280",
};

const TYPE_SHAPES: Record<string, string> = {
  PERSON: "circle",
  ORGANIZATION: "diamond",
  LOCATION: "square",
  EVENT: "triangle",
  OBJECT: "hexagon",
};

export function GraphVisualization({ nodes, edges }: GraphVisualizationProps) {
  const containerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!containerRef.current || nodes.length === 0) return;

    // NVL initialization would go here in production.
    // For the demo scaffold, we render a placeholder that shows graph stats.
    const container = containerRef.current;
    container.innerHTML = "";

    const info = document.createElement("div");
    info.style.cssText =
      "position:absolute;bottom:16px;left:16px;background:rgba(0,0,0,0.7);padding:12px 16px;border-radius:8px;font-size:13px;";
    info.innerHTML = `
      <div style="margin-bottom:4px"><strong>${nodes.length}</strong> nodes &middot; <strong>${edges.length}</strong> relationships</div>
      <div style="display:flex;gap:12px;flex-wrap:wrap">
        ${Object.entries(AGENT_COLORS)
          .filter(([k]) => k !== "default")
          .map(
            ([name, color]) =>
              `<span style="display:flex;align-items:center;gap:4px">
                <span style="width:8px;height:8px;border-radius:50%;background:${color};display:inline-block"></span>
                ${name}
              </span>`,
          )
          .join("")}
      </div>
    `;
    container.appendChild(info);

    // Draw placeholder circles for each node
    const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
    svg.setAttribute("width", "100%");
    svg.setAttribute("height", "100%");
    svg.style.position = "absolute";
    svg.style.top = "0";
    svg.style.left = "0";

    nodes.forEach((node, i) => {
      const cx = 100 + (i % 10) * 80;
      const cy = 100 + Math.floor(i / 10) * 80;
      const color = AGENT_COLORS[node.agent ?? "default"] ?? AGENT_COLORS["default"];

      const circle = document.createElementNS("http://www.w3.org/2000/svg", "circle");
      circle.setAttribute("cx", String(cx));
      circle.setAttribute("cy", String(cy));
      circle.setAttribute("r", "20");
      circle.setAttribute("fill", color!);
      circle.setAttribute("opacity", "0.8");
      svg.appendChild(circle);

      const text = document.createElementNS("http://www.w3.org/2000/svg", "text");
      text.setAttribute("x", String(cx));
      text.setAttribute("y", String(cy + 35));
      text.setAttribute("text-anchor", "middle");
      text.setAttribute("fill", "#999");
      text.setAttribute("font-size", "10");
      text.textContent = node.label?.slice(0, 12) ?? "";
      svg.appendChild(text);
    });

    container.insertBefore(svg, info);
  }, [nodes, edges]);

  return (
    <div
      ref={containerRef}
      style={{
        width: "100%",
        height: "100%",
        background: "#0a0a0a",
        position: "relative",
      }}
    >
      {nodes.length === 0 && (
        <div
          style={{
            position: "absolute",
            top: "50%",
            left: "50%",
            transform: "translate(-50%, -50%)",
            color: "#666",
            textAlign: "center",
          }}
        >
          <p style={{ fontSize: 48, margin: 0 }}>&#x1f4a0;</p>
          <p>Waiting for agents to create entities...</p>
          <p style={{ fontSize: 12, color: "#444" }}>
            Graph will update automatically every 5 seconds
          </p>
        </div>
      )}
    </div>
  );
}
