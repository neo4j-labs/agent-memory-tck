"use client";

import { useEffect, useState } from "react";
import { Box, Button, Heading, Stack, Text } from "@chakra-ui/react";

interface SessionRow {
  id: string;
  title: string;
  updatedAt: string;
  messageCount: number;
}

export function SessionsPanel({
  currentConversationId,
  onResume,
  refreshKey,
}: {
  currentConversationId: string | null;
  onResume: (conversationId: string) => void;
  refreshKey: number;
}) {
  const [sessions, setSessions] = useState<SessionRow[]>([]);

  useEffect(() => {
    let cancelled = false;
    fetch("/api/sessions")
      .then((r) => r.json())
      .then((d) => {
        if (!cancelled) setSessions(d.sessions ?? []);
      })
      .catch(() => {});
    return () => {
      cancelled = true;
    };
  }, [refreshKey]);

  return (
    <Stack gap={3}>
      <Heading size="sm" color="labs.600">
        Your sessions
      </Heading>
      <Text fontSize="xs" color="gray.500">
        Anonymous cookie session. Resume any past conversation to see context recall in action.
      </Text>
      {sessions.length === 0 ? (
        <Text fontSize="sm" color="gray.400">
          No sessions yet.
        </Text>
      ) : (
        sessions.map((s) => (
          <Box
            key={s.id}
            p={3}
            borderWidth="1px"
            borderRadius="md"
            bg={s.id === currentConversationId ? "labs.50" : undefined}
          >
            <Text fontSize="sm" fontWeight="semibold">
              {s.title}
            </Text>
            <Text fontSize="xs" color="gray.500">
              {s.messageCount} messages · {new Date(s.updatedAt).toLocaleString()}
            </Text>
            {s.id !== currentConversationId && (
              <Button
                size="xs"
                mt={2}
                variant="outline"
                colorPalette="purple"
                onClick={() => onResume(s.id)}
              >
                Resume
              </Button>
            )}
          </Box>
        ))
      )}
    </Stack>
  );
}
