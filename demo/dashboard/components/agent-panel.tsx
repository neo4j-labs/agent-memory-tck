"use client";

import { Badge, Card, HStack, SimpleGrid, Stat, Text } from "@chakra-ui/react";
import { Status } from "@/components/ui/status";

export interface AgentStatus {
  name: string;
  framework: string;
  language: string;
  color: string;
  healthy?: boolean;
  entityCount: number;
  traceCount: number;
  messageCount: number;
}

const LANGUAGE_PALETTES: Record<string, string> = {
  Python: "blue",
  TypeScript: "cyan",
  Go: "teal",
  "C#": "purple",
  R: "green",
};

export function AgentPanel({ agent }: { agent: AgentStatus }) {
  const palette = LANGUAGE_PALETTES[agent.language] ?? "gray";

  return (
    <Card.Root
      size="sm"
      variant="outline"
      borderColor={`${agent.color}4D`}
      bg={`${agent.color}0F`}
      mb="2"
    >
      <Card.Body p="3" gap="2">
        <HStack justify="space-between">
          <HStack gap="2">
            <Status colorPalette={agent.healthy ? "green" : "red"} />
            <Text fontWeight="semibold" textStyle="sm">
              {agent.name}
            </Text>
          </HStack>
          <Badge size="sm" colorPalette={palette} variant="subtle">
            {agent.language}
          </Badge>
        </HStack>

        <Text color="fg.muted" textStyle="xs">
          {agent.framework}
        </Text>

        <SimpleGrid columns={3} gap="2" mt="1">
          <Stat.Root size="sm">
            <Stat.ValueText textStyle="md" fontWeight="bold">
              {agent.entityCount}
            </Stat.ValueText>
            <Stat.Label textStyle="2xs" color="fg.muted">
              Entities
            </Stat.Label>
          </Stat.Root>
          <Stat.Root size="sm">
            <Stat.ValueText textStyle="md" fontWeight="bold">
              {agent.traceCount}
            </Stat.ValueText>
            <Stat.Label textStyle="2xs" color="fg.muted">
              Traces
            </Stat.Label>
          </Stat.Root>
          <Stat.Root size="sm">
            <Stat.ValueText textStyle="md" fontWeight="bold">
              {agent.messageCount}
            </Stat.ValueText>
            <Stat.Label textStyle="2xs" color="fg.muted">
              Messages
            </Stat.Label>
          </Stat.Root>
        </SimpleGrid>
      </Card.Body>
    </Card.Root>
  );
}
