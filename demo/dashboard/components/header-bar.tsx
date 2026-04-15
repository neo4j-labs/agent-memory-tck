"use client";

import {
  Badge,
  Flex,
  HStack,
  Heading,
  IconButton,
  Stat,
} from "@chakra-ui/react";
import { LuRefreshCw } from "react-icons/lu";

interface HeaderBarProps {
  nodeCount: number;
  edgeCount: number;
  agentCount: number;
  onRefresh: () => void;
}

export function HeaderBar({
  nodeCount,
  edgeCount,
  agentCount,
  onRefresh,
}: HeaderBarProps) {
  return (
    <Flex
      h="48px"
      align="center"
      px="4"
      borderBottomWidth="1px"
      borderColor="border.subtle"
      bg="bg.panel"
      justify="space-between"
      flexShrink={0}
    >
      <HStack gap="3">
        <Heading size="md" fontWeight="bold">
          Agent Memory
        </Heading>
        <Badge colorPalette="purple" variant="subtle" size="sm">
          Polyglot Demo
        </Badge>
      </HStack>

      <HStack gap="6">
        <HStack gap="4">
          <Stat.Root size="sm">
            <HStack gap="1">
              <Stat.Label color="fg.muted">Nodes</Stat.Label>
              <Stat.ValueText color="blue.fg" fontWeight="bold" textStyle="sm">
                {nodeCount}
              </Stat.ValueText>
            </HStack>
          </Stat.Root>
          <Stat.Root size="sm">
            <HStack gap="1">
              <Stat.Label color="fg.muted">Edges</Stat.Label>
              <Stat.ValueText color="green.fg" fontWeight="bold" textStyle="sm">
                {edgeCount}
              </Stat.ValueText>
            </HStack>
          </Stat.Root>
          <Stat.Root size="sm">
            <HStack gap="1">
              <Stat.Label color="fg.muted">Agents</Stat.Label>
              <Stat.ValueText
                color="orange.fg"
                fontWeight="bold"
                textStyle="sm"
              >
                {agentCount}
              </Stat.ValueText>
            </HStack>
          </Stat.Root>
        </HStack>

        <IconButton
          aria-label="Refresh"
          size="sm"
          variant="ghost"
          onClick={onRefresh}
        >
          <LuRefreshCw />
        </IconButton>
      </HStack>
    </Flex>
  );
}
