"use client";

import { useEffect, useState } from "react";
import {
  Badge,
  Box,
  Card,
  CloseButton,
  DataList,
  Drawer,
  HStack,
  Heading,
  Portal,
  Separator,
  Spinner,
  Stack,
  Text,
} from "@chakra-ui/react";
import { LuArrowRight, LuArrowLeft } from "react-icons/lu";

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
  sage: "#ec4899",
  rune: "#2F9E44",
  shared: "#64748b",
};

const AGENT_LABELS: Record<string, string> = {
  lenny: "Lenny (Python/PydanticAI)",
  scout: "Scout (TypeScript/Vercel AI SDK)",
  forge: "Forge (Go/Custom HTTP)",
  atlas: "Atlas (Python/LangGraph)",
  sage: "Sage (C#/Semantic Kernel)",
  rune: "Rune (R/ellmer)",
  shared: "Shared (all agents)",
};

const TYPE_PALETTES: Record<string, string> = {
  Person: "blue",
  Organization: "purple",
  Location: "green",
  Event: "orange",
  Entity: "teal",
  Fact: "cyan",
  Message: "gray",
  ReasoningTrace: "yellow",
  ReasoningStep: "gray",
  ToolCall: "red",
  Preference: "purple",
  Conversation: "gray",
};

const FILTERED_PROPS = new Set(["embedding", "id", "task_embedding"]);

function formatValue(val: unknown): string {
  if (val === null || val === undefined) return "—";

  if (typeof val === "object" && val !== null) {
    const obj = val as Record<string, unknown>;

    if ("year" in obj && "month" in obj && "day" in obj) {
      const y = extractInt(obj.year);
      const m = extractInt(obj.month);
      const d = extractInt(obj.day);
      if (y && m && d) return `${y}-${String(m).padStart(2, "0")}-${String(d).padStart(2, "0")}`;
    }

    if ("low" in obj && "high" in obj) {
      return String(extractInt(obj));
    }

    if (Array.isArray(val)) {
      if (val.length > 5) return `[${val.slice(0, 3).join(", ")}, ... +${val.length - 3}]`;
      return `[${val.join(", ")}]`;
    }

    const str = JSON.stringify(val);
    return str.length > 80 ? str.slice(0, 77) + "…" : str;
  }

  const str = String(val);
  return str.length > 80 ? str.slice(0, 77) + "…" : str;
}

function extractInt(val: unknown): number | null {
  if (typeof val === "number") return val;
  if (typeof val === "object" && val !== null && "low" in (val as Record<string, unknown>)) {
    return (val as { low: number }).low;
  }
  return null;
}

interface NodeDetailDrawerProps {
  node: NodeData | null;
  onClose: () => void;
  onNavigateToNode?: (nodeId: string) => void;
}

export function NodeDetailDrawer({
  node,
  onClose,
  onNavigateToNode,
}: NodeDetailDrawerProps) {
  const [connections, setConnections] = useState<Connection[]>([]);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!node) return;
    setLoading(true);
    fetch("/api/node-detail", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ nodeId: node.id }),
    })
      .then((r) => r.json())
      .then((data) => setConnections(data.connections ?? []))
      .catch(() => setConnections([]))
      .finally(() => setLoading(false));
  }, [node]);

  const properties = node?.properties
    ? Object.entries(node.properties).filter(([k]) => !FILTERED_PROPS.has(k))
    : [];

  const agentColor = AGENT_COLORS[node?.agent ?? "shared"];
  const agentLabel = AGENT_LABELS[node?.agent ?? "shared"] ?? "Unknown";
  const typePalette = TYPE_PALETTES[node?.type ?? ""] ?? "gray";

  return (
    <Drawer.Root
      open={!!node}
      onOpenChange={(e) => {
        if (!e.open) onClose();
      }}
      placement="end"
      size="sm"
      modal={false}
    >
      <Portal>
        <Drawer.Positioner>
          <Drawer.Content
            bg="bg.panel"
            borderLeftWidth="1px"
            borderColor="border.subtle"
            shadow="xl"
          >
            <Drawer.Header
              borderBottomWidth="1px"
              borderColor="border.subtle"
              pb="3"
            >
              <Stack gap="2" flex="1">
                <HStack gap="2">
                  <Badge
                    colorPalette={typePalette}
                    variant="subtle"
                    size="sm"
                  >
                    {node?.type}
                  </Badge>
                  <Box
                    w="6px"
                    h="6px"
                    rounded="full"
                    bg={agentColor}
                    shadow={`0 0 4px ${agentColor}`}
                  />
                </HStack>
                <Drawer.Title textStyle="lg">{node?.label}</Drawer.Title>
                <Text textStyle="xs" color="fg.muted">
                  Created by{" "}
                  <Text as="span" color={agentColor} fontWeight="semibold">
                    {agentLabel}
                  </Text>
                </Text>
              </Stack>
              <Drawer.CloseTrigger asChild>
                <CloseButton size="sm" />
              </Drawer.CloseTrigger>
            </Drawer.Header>

            <Drawer.Body pt="4">
              <Stack gap="5">
                {properties.length > 0 && (
                  <Box>
                    <Heading
                      size="xs"
                      color="fg.muted"
                      textTransform="uppercase"
                      letterSpacing="wide"
                      mb="3"
                    >
                      Properties
                    </Heading>
                    <Card.Root variant="outline" size="sm">
                      <Card.Body p="0">
                        <DataList.Root size="sm" p="2">
                          {properties.map(([key, value]) => (
                            <DataList.Item key={key} py="1.5">
                              <DataList.ItemLabel
                                color="fg.muted"
                                minW="110px"
                                fontFamily="mono"
                                textStyle="xs"
                              >
                                {key}
                              </DataList.ItemLabel>
                              <DataList.ItemValue
                                textStyle="xs"
                                maxW="220px"
                                wordBreak="break-all"
                              >
                                {formatValue(value)}
                              </DataList.ItemValue>
                            </DataList.Item>
                          ))}
                        </DataList.Root>
                      </Card.Body>
                    </Card.Root>
                  </Box>
                )}

                <Separator />

                <Box>
                  <Heading
                    size="xs"
                    color="fg.muted"
                    textTransform="uppercase"
                    letterSpacing="wide"
                    mb="3"
                  >
                    Connections ({connections.length})
                  </Heading>

                  {loading ? (
                    <HStack gap="2" py="4">
                      <Spinner size="sm" />
                      <Text textStyle="xs" color="fg.muted">
                        Loading connections…
                      </Text>
                    </HStack>
                  ) : connections.length === 0 ? (
                    <Text textStyle="xs" color="fg.subtle" py="2">
                      No connections found
                    </Text>
                  ) : (
                    <Stack gap="1">
                      {connections.map((conn) => {
                        const connPalette =
                          TYPE_PALETTES[conn.type] ?? "gray";
                        return (
                          <HStack
                            key={`${conn.id}-${conn.rel}`}
                            py="1.5"
                            px="2"
                            rounded="md"
                            _hover={{ bg: "bg.subtle" }}
                            cursor={
                              onNavigateToNode ? "pointer" : "default"
                            }
                            onClick={() =>
                              onNavigateToNode?.(conn.id)
                            }
                            gap="2"
                          >
                            <Box color="fg.muted" flexShrink={0}>
                              {conn.direction === "out" ? (
                                <LuArrowRight size={12} />
                              ) : (
                                <LuArrowLeft size={12} />
                              )}
                            </Box>
                            <Text
                              color="fg.muted"
                              fontFamily="mono"
                              textStyle="2xs"
                              flexShrink={0}
                              minW="80px"
                            >
                              {conn.rel}
                            </Text>
                            <Text textStyle="xs" flex="1" truncate>
                              {conn.label}
                            </Text>
                            <Badge
                              size="sm"
                              variant="outline"
                              colorPalette={connPalette}
                              flexShrink={0}
                            >
                              {conn.type}
                            </Badge>
                          </HStack>
                        );
                      })}
                    </Stack>
                  )}
                </Box>
              </Stack>
            </Drawer.Body>
          </Drawer.Content>
        </Drawer.Positioner>
      </Portal>
    </Drawer.Root>
  );
}
