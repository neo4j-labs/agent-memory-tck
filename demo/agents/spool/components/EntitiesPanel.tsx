"use client";

import { useEffect, useState } from "react";
import { Badge, Box, Heading, Stack, Text } from "@chakra-ui/react";

interface Entity {
  id: string;
  name: string;
  type: string;
  description?: string;
}

export function EntitiesPanel({ refreshKey }: { refreshKey: number }) {
  const [entities, setEntities] = useState<Entity[]>([]);

  useEffect(() => {
    let cancelled = false;
    fetch("/api/entities")
      .then((r) => r.json())
      .then((data) => {
        if (!cancelled) setEntities(data.entities ?? []);
      })
      .catch(() => {});
    return () => {
      cancelled = true;
    };
  }, [refreshKey]);

  if (entities.length === 0) {
    return (
      <Text color="gray.400" textAlign="center" mt={6}>
        No entities yet — chat with the agent and the service will extract them.
      </Text>
    );
  }

  return (
    <Stack gap={3}>
      <Heading size="sm" color="labs.600">
        Entities ({entities.length})
      </Heading>
      {entities.map((e) => (
        <Box key={e.id} p={3} borderWidth="1px" borderRadius="md">
          <Text fontSize="sm" fontWeight="semibold">
            {e.name}{" "}
            <Badge ml={1} colorPalette="purple">
              {e.type}
            </Badge>
          </Text>
          {e.description && (
            <Text fontSize="xs" color="gray.600" mt={1}>
              {e.description}
            </Text>
          )}
        </Box>
      ))}
    </Stack>
  );
}
