"use client";

import { useEffect, useState } from "react";
import { Box, HStack, Heading, Text, Timeline, VStack } from "@chakra-ui/react";

interface ActivityItem {
  id: string;
  label: string;
  type: string;
  agent: string;
  timestamp?: string;
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

const TYPE_VERBS: Record<string, string> = {
  Person: "discovered",
  Organization: "identified",
  Location: "mapped",
  Event: "recorded",
  Entity: "created entity",
  Fact: "recorded fact",
  Preference: "noted preference",
  Message: "sent message",
  ReasoningTrace: "started reasoning",
  ReasoningStep: "reasoning step",
  ToolCall: "called tool",
};

export function ActivityFeed() {
  const [items, setItems] = useState<ActivityItem[]>([]);

  useEffect(() => {
    const fetchActivity = async () => {
      try {
        const res = await fetch("/api/activity");
        const data = await res.json();
        setItems(data.items ?? []);
      } catch {
        /* ignore */
      }
    };
    fetchActivity();
    const interval = setInterval(fetchActivity, 5000);
    return () => clearInterval(interval);
  }, []);

  if (items.length === 0) return null;

  return (
    <Box px="3" py="2">
      <Heading size="xs" color="fg.muted" mb="2" textTransform="uppercase" letterSpacing="wide">
        Activity Feed
      </Heading>
      <Box maxH="300px" overflowY="auto">
        <Timeline.Root size="sm">
          {items.slice(0, 20).map((item) => {
            const color = AGENT_COLORS[item.agent] ?? AGENT_COLORS.shared;
            const verb = TYPE_VERBS[item.type] ?? "created";
            const label =
              item.label.length > 30
                ? item.label.slice(0, 30) + "…"
                : item.label;

            return (
              <Timeline.Item key={item.id}>
                <Timeline.Connector>
                  <Timeline.Separator />
                  <Timeline.Indicator bg={color} />
                </Timeline.Connector>
                <Timeline.Content pb="2">
                  <Text textStyle="xs" lineClamp={1}>
                    <Text as="span" color={color} fontWeight="bold">
                      {item.agent}
                    </Text>{" "}
                    <Text as="span" color="fg.muted">
                      {verb}
                    </Text>{" "}
                    {label}
                  </Text>
                </Timeline.Content>
              </Timeline.Item>
            );
          })}
        </Timeline.Root>
      </Box>
    </Box>
  );
}
