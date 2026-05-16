"use client";

import { useEffect, useState } from "react";
import { Box, Heading, Stack, Text } from "@chakra-ui/react";

interface Reflection {
  id: string;
  content: string;
  createdAt: string;
}

interface Observation {
  id: string;
  content: string;
  createdAt: string;
}

interface ContextData {
  reflections: Reflection[];
  observations: Observation[];
}

export function ContextPanel({
  conversationId,
  refreshKey,
}: {
  conversationId: string | null;
  refreshKey: number;
}) {
  const [data, setData] = useState<ContextData>({ reflections: [], observations: [] });

  useEffect(() => {
    if (!conversationId) return;
    let cancelled = false;
    fetch(`/api/context?conversationId=${encodeURIComponent(conversationId)}`)
      .then((r) => r.json())
      .then((d) => {
        if (!cancelled)
          setData({
            reflections: d.reflections ?? [],
            observations: d.observations ?? [],
          });
      })
      .catch(() => {});
    return () => {
      cancelled = true;
    };
  }, [conversationId, refreshKey]);

  if (!conversationId) {
    return (
      <Text color="gray.400" textAlign="center" mt={6}>
        Start a chat to see derived context.
      </Text>
    );
  }

  return (
    <Stack gap={4}>
      <Box>
        <Heading size="sm" color="labs.600">
          Reflections ({data.reflections.length})
        </Heading>
        <Text fontSize="xs" color="gray.500" mb={2}>
          Long-horizon takeaways about the user, derived from this and prior conversations.
        </Text>
        {data.reflections.length === 0 ? (
          <Text fontSize="sm" color="gray.400">
            None yet.
          </Text>
        ) : (
          <Stack gap={2}>
            {data.reflections.map((r) => (
              <Box key={r.id} p={2} borderWidth="1px" borderRadius="md" bg="labs.50">
                <Text fontSize="sm">{r.content}</Text>
              </Box>
            ))}
          </Stack>
        )}
      </Box>

      <Box>
        <Heading size="sm" color="labs.600">
          Observations ({data.observations.length})
        </Heading>
        <Text fontSize="xs" color="gray.500" mb={2}>
          Short, factual notes extracted from recent messages.
        </Text>
        {data.observations.length === 0 ? (
          <Text fontSize="sm" color="gray.400">
            None yet.
          </Text>
        ) : (
          <Stack gap={2}>
            {data.observations.map((o) => (
              <Box key={o.id} p={2} borderWidth="1px" borderRadius="md">
                <Text fontSize="sm">{o.content}</Text>
              </Box>
            ))}
          </Stack>
        )}
      </Box>
    </Stack>
  );
}
