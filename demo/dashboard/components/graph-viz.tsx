"use client";

/**
 * Force-directed graph visualization using Canvas.
 *
 * Renders the shared knowledge graph with:
 *   - Nodes colored by agent (Lenny=blue, Scout=green, Forge=orange, Atlas=purple)
 *   - Node size by type (entities large, messages small)
 *   - Edge lines with labels
 *   - Click to select a node
 *   - Drag to reposition nodes
 *   - Hover for tooltips
 */

import { useEffect, useRef, useCallback, useState } from "react";

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

interface SimNode extends GraphNode {
  x: number;
  y: number;
  vx: number;
  vy: number;
  radius: number;
  color: string;
}

interface GraphVisualizationProps {
  nodes: GraphNode[];
  edges: GraphEdge[];
  onNodeClick?: (node: GraphNode) => void;
}

const AGENT_COLORS: Record<string, string> = {
  lenny: "#3b82f6",
  scout: "#22c55e",
  forge: "#f97316",
  atlas: "#8b5cf6",
  sage: "#ec4899",
  rune: "#2F9E44",
  shared: "#64748b",
  unknown: "#475569",
};

const TYPE_SIZES: Record<string, number> = {
  Person: 22,
  Organization: 24,
  Location: 18,
  Event: 18,
  Entity: 20,
  Conversation: 14,
  Message: 10,
  Fact: 10,
  Preference: 10,
  ReasoningTrace: 16,
  ReasoningStep: 10,
  ToolCall: 8,
  Tool: 8,
};

function getNodeColor(agent: string | undefined): string {
  return AGENT_COLORS[agent ?? "shared"] ?? AGENT_COLORS["shared"]!;
}

function getNodeRadius(type: string): number {
  return TYPE_SIZES[type] ?? 12;
}

export function GraphVisualization({ nodes, edges, onNodeClick }: GraphVisualizationProps) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const simNodesRef = useRef<SimNode[]>([]);
  const animRef = useRef<number>(0);
  const dragRef = useRef<{ node: SimNode | null; offsetX: number; offsetY: number }>({ node: null, offsetX: 0, offsetY: 0 });
  const hoverRef = useRef<SimNode | null>(null);
  const [dimensions, setDimensions] = useState({ width: 800, height: 600 });

  // Initialize simulation nodes
  useEffect(() => {
    if (nodes.length === 0) {
      simNodesRef.current = [];
      return;
    }

    const cx = dimensions.width / 2;
    const cy = dimensions.height / 2;

    // Keep existing positions for nodes that already exist
    const existingPositions = new Map<string, { x: number; y: number }>();
    for (const sn of simNodesRef.current) {
      existingPositions.set(sn.id, { x: sn.x, y: sn.y });
    }

    simNodesRef.current = nodes.map((n, i) => {
      const existing = existingPositions.get(n.id);
      const angle = (i / nodes.length) * Math.PI * 2;
      const spread = Math.min(dimensions.width, dimensions.height) * 0.35;
      return {
        ...n,
        x: existing?.x ?? cx + Math.cos(angle) * spread + (Math.random() - 0.5) * 60,
        y: existing?.y ?? cy + Math.sin(angle) * spread + (Math.random() - 0.5) * 60,
        vx: 0,
        vy: 0,
        radius: getNodeRadius(n.type),
        color: getNodeColor(n.agent),
      };
    });
  }, [nodes, dimensions]);

  // Resize observer
  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const parent = canvas.parentElement;
    if (!parent) return;

    const ro = new ResizeObserver(() => {
      const { width, height } = parent.getBoundingClientRect();
      setDimensions({ width, height });
    });
    ro.observe(parent);
    return () => ro.disconnect();
  }, []);

  // Animation loop with force simulation
  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext("2d");
    if (!ctx) return;

    const nodeMap = new Map<string, SimNode>();

    const tick = () => {
      const simNodes = simNodesRef.current;
      if (simNodes.length === 0) {
        // Draw empty state
        ctx.clearRect(0, 0, dimensions.width, dimensions.height);
        ctx.fillStyle = "#666";
        ctx.font = "14px system-ui";
        ctx.textAlign = "center";
        ctx.fillText("Waiting for agents to create entities...", dimensions.width / 2, dimensions.height / 2);
        ctx.font = "12px system-ui";
        ctx.fillStyle = "#444";
        ctx.fillText("Run: uv run python demo/seed-data.py", dimensions.width / 2, dimensions.height / 2 + 24);
        animRef.current = requestAnimationFrame(tick);
        return;
      }

      nodeMap.clear();
      for (const n of simNodes) nodeMap.set(n.id, n);

      // Force simulation
      const alpha = 0.15;
      const repulsion = 2000;
      const attraction = 0.005;
      const centerGravity = 0.01;
      const cx = dimensions.width / 2;
      const cy = dimensions.height / 2;

      // Repulsion between all nodes
      for (let i = 0; i < simNodes.length; i++) {
        for (let j = i + 1; j < simNodes.length; j++) {
          const a = simNodes[i]!;
          const b = simNodes[j]!;
          let dx = b.x - a.x;
          let dy = b.y - a.y;
          const dist = Math.sqrt(dx * dx + dy * dy) || 1;
          const force = repulsion / (dist * dist);
          dx = (dx / dist) * force * alpha;
          dy = (dy / dist) * force * alpha;
          a.vx -= dx;
          a.vy -= dy;
          b.vx += dx;
          b.vy += dy;
        }
      }

      // Attraction along edges
      for (const edge of edges) {
        const a = nodeMap.get(edge.source);
        const b = nodeMap.get(edge.target);
        if (!a || !b) continue;
        const dx = b.x - a.x;
        const dy = b.y - a.y;
        const force = attraction * alpha;
        a.vx += dx * force;
        a.vy += dy * force;
        b.vx -= dx * force;
        b.vy -= dy * force;
      }

      // Center gravity
      for (const n of simNodes) {
        n.vx += (cx - n.x) * centerGravity * alpha;
        n.vy += (cy - n.y) * centerGravity * alpha;
      }

      // Apply velocities with damping
      for (const n of simNodes) {
        if (dragRef.current.node === n) continue;
        n.vx *= 0.85;
        n.vy *= 0.85;
        n.x += n.vx;
        n.y += n.vy;
        // Boundary clamping
        n.x = Math.max(n.radius, Math.min(dimensions.width - n.radius, n.x));
        n.y = Math.max(n.radius, Math.min(dimensions.height - n.radius, n.y));
      }

      // Draw
      ctx.clearRect(0, 0, dimensions.width, dimensions.height);

      // Edges
      ctx.lineWidth = 0.5;
      for (const edge of edges) {
        const a = nodeMap.get(edge.source);
        const b = nodeMap.get(edge.target);
        if (!a || !b) continue;
        ctx.strokeStyle = "#333";
        ctx.beginPath();
        ctx.moveTo(a.x, a.y);
        ctx.lineTo(b.x, b.y);
        ctx.stroke();

        // Edge label
        const mx = (a.x + b.x) / 2;
        const my = (a.y + b.y) / 2;
        ctx.fillStyle = "#555";
        ctx.font = "8px system-ui";
        ctx.textAlign = "center";
        ctx.fillText(edge.type, mx, my - 3);
      }

      // Nodes
      for (const n of simNodes) {
        // Glow for hovered node
        if (hoverRef.current === n) {
          ctx.shadowColor = n.color;
          ctx.shadowBlur = 15;
        }

        ctx.fillStyle = n.color;
        ctx.globalAlpha = 0.9;
        ctx.beginPath();
        ctx.arc(n.x, n.y, n.radius, 0, Math.PI * 2);
        ctx.fill();

        ctx.shadowBlur = 0;
        ctx.globalAlpha = 1;

        // Border
        ctx.strokeStyle = hoverRef.current === n ? "#fff" : "rgba(255,255,255,0.2)";
        ctx.lineWidth = hoverRef.current === n ? 2 : 1;
        ctx.stroke();

        // Label
        const label = n.label.length > 18 ? n.label.slice(0, 16) + "..." : n.label;
        ctx.fillStyle = "#ccc";
        ctx.font = n.radius > 16 ? "11px system-ui" : "9px system-ui";
        ctx.textAlign = "center";
        ctx.fillText(label, n.x, n.y + n.radius + 12);

        // Type badge
        ctx.fillStyle = "#666";
        ctx.font = "7px system-ui";
        ctx.fillText(n.type, n.x, n.y + n.radius + 22);
      }

      // Tooltip for hovered node
      if (hoverRef.current) {
        const n = hoverRef.current;
        const text = `${n.label} (${n.type}) — ${n.agent ?? "shared"}`;
        ctx.fillStyle = "rgba(0,0,0,0.85)";
        const tw = ctx.measureText(text).width;
        ctx.fillRect(n.x - tw / 2 - 6, n.y - n.radius - 28, tw + 12, 20);
        ctx.fillStyle = "#fff";
        ctx.font = "11px system-ui";
        ctx.fillText(text, n.x, n.y - n.radius - 14);
      }

      animRef.current = requestAnimationFrame(tick);
    };

    animRef.current = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(animRef.current);
  }, [edges, dimensions]);

  // Mouse interaction
  const findNode = useCallback((x: number, y: number): SimNode | null => {
    for (const n of simNodesRef.current) {
      const dx = x - n.x;
      const dy = y - n.y;
      if (dx * dx + dy * dy < n.radius * n.radius * 1.5) return n;
    }
    return null;
  }, []);

  const handleMouseDown = useCallback((e: React.MouseEvent) => {
    const rect = canvasRef.current?.getBoundingClientRect();
    if (!rect) return;
    const x = e.clientX - rect.left;
    const y = e.clientY - rect.top;
    const node = findNode(x, y);
    if (node) {
      dragRef.current = { node, offsetX: x - node.x, offsetY: y - node.y };
    }
  }, [findNode]);

  const handleMouseMove = useCallback((e: React.MouseEvent) => {
    const rect = canvasRef.current?.getBoundingClientRect();
    if (!rect) return;
    const x = e.clientX - rect.left;
    const y = e.clientY - rect.top;

    if (dragRef.current.node) {
      dragRef.current.node.x = x - dragRef.current.offsetX;
      dragRef.current.node.y = y - dragRef.current.offsetY;
      dragRef.current.node.vx = 0;
      dragRef.current.node.vy = 0;
    } else {
      const node = findNode(x, y);
      hoverRef.current = node;
      if (canvasRef.current) {
        canvasRef.current.style.cursor = node ? "pointer" : "default";
      }
    }
  }, [findNode]);

  const handleMouseUp = useCallback(() => {
    dragRef.current = { node: null, offsetX: 0, offsetY: 0 };
  }, []);

  const handleClick = useCallback((e: React.MouseEvent) => {
    const rect = canvasRef.current?.getBoundingClientRect();
    if (!rect) return;
    const x = e.clientX - rect.left;
    const y = e.clientY - rect.top;
    const node = findNode(x, y);
    if (node && onNodeClick) {
      onNodeClick(node);
    }
  }, [findNode, onNodeClick]);

  return (
    <div style={{ width: "100%", height: "100%", position: "relative", background: "#0a0a0a" }}>
      <canvas
        ref={canvasRef}
        width={dimensions.width}
        height={dimensions.height}
        style={{ display: "block" }}
        onMouseDown={handleMouseDown}
        onMouseMove={handleMouseMove}
        onMouseUp={handleMouseUp}
        onMouseLeave={handleMouseUp}
        onClick={handleClick}
      />
      {/* Legend */}
      <div style={{
        position: "absolute", bottom: 12, left: 12, background: "rgba(0,0,0,0.8)",
        padding: "8px 12px", borderRadius: 8, fontSize: 11, display: "flex", gap: 14, alignItems: "center"
      }}>
        <span style={{ color: "#999" }}>{nodes.length} nodes &middot; {edges.length} edges</span>
        <span style={{ borderLeft: "1px solid #333", paddingLeft: 12, display: "flex", gap: 10 }}>
          {Object.entries(AGENT_COLORS).filter(([k]) => !["unknown"].includes(k)).map(([name, color]) => (
            <span key={name} style={{ display: "flex", alignItems: "center", gap: 4 }}>
              <span style={{ width: 8, height: 8, borderRadius: "50%", background: color, display: "inline-block" }} />
              <span style={{ color: "#999" }}>{name}</span>
            </span>
          ))}
        </span>
      </div>
    </div>
  );
}
