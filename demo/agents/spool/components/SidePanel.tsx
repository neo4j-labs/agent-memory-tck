"use client";

import { Box, Tabs } from "@chakra-ui/react";
import { ReasoningPanel } from "./ReasoningPanel";
import { EntitiesPanel } from "./EntitiesPanel";
import { ContextPanel } from "./ContextPanel";
import { SessionsPanel } from "./SessionsPanel";

export function SidePanel({
  conversationId,
  onResume,
  refreshKey,
}: {
  conversationId: string | null;
  onResume: (id: string) => void;
  refreshKey: number;
}) {
  return (
    <Box h="100%" overflowY="auto" p={4}>
      <Tabs.Root defaultValue="reasoning" variant="line">
        <Tabs.List>
          <Tabs.Trigger value="reasoning">Reasoning</Tabs.Trigger>
          <Tabs.Trigger value="entities">Entities</Tabs.Trigger>
          <Tabs.Trigger value="context">Context</Tabs.Trigger>
          <Tabs.Trigger value="sessions">Sessions</Tabs.Trigger>
        </Tabs.List>

        <Tabs.Content value="reasoning">
          <ReasoningPanel conversationId={conversationId} refreshKey={refreshKey} />
        </Tabs.Content>
        <Tabs.Content value="entities">
          <EntitiesPanel refreshKey={refreshKey} />
        </Tabs.Content>
        <Tabs.Content value="context">
          <ContextPanel conversationId={conversationId} refreshKey={refreshKey} />
        </Tabs.Content>
        <Tabs.Content value="sessions">
          <SessionsPanel
            currentConversationId={conversationId}
            onResume={onResume}
            refreshKey={refreshKey}
          />
        </Tabs.Content>
      </Tabs.Root>
    </Box>
  );
}
