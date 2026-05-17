"use client";

import { useEffect, useState } from "react";
import { Badge, Box, Heading, Stack, Text } from "@chakra-ui/react";

interface ReasoningStep {
  id: string;
  reasoning: string;
  actionTaken: string;
  result?: string;
  createdAt: string;
}

interface ToolCall {
  id: string;
  stepId: string;
  toolName: string;
  arguments: Record<string, unknown>;
  status: string;
  durationMs?: number;
}

export function ReasoningPanel({
  conversationId,
  refreshKey,
}: {
  conversationId: string | null;
  refreshKey: number;
}) {
  const [steps, setSteps] = useState<ReasoningStep[]>([]);
  const [toolCalls, setToolCalls] = useState<ToolCall[]>([]);

  useEffect(() => {
    if (!conversationId) return;
    let cancelled = false;
    fetch(`/api/trace?conversationId=${encodeURIComponent(conversationId)}`)
      .then((r) => r.json())
      .then((data) => {
        if (cancelled) return;
        setSteps(data.steps ?? []);
        setToolCalls(data.toolCalls ?? []);
      })
      .catch(() => {});
    return () => {
      cancelled = true;
    };
  }, [conversationId, refreshKey]);

  if (!conversationId) {
    return (
      <Text color="gray.400" textAlign="center" mt={6}>
        Start a chat to see the reasoning trace.
      </Text>
    );
  }

  if (steps.length === 0) {
    return (
      <Text color="gray.400" textAlign="center" mt={6}>
        No reasoning steps yet.
      </Text>
    );
  }

  return (
    <Stack gap={3}>
      <Heading size="sm" color="labs.600">
        Reasoning trace
      </Heading>
      {steps.map((step) => {
        const stepTools = toolCalls.filter((t) => t.stepId === step.id);
        return (
          <Box key={step.id} p={3} borderWidth="1px" borderRadius="md">
            <Text fontSize="xs" color="gray.500">
              {new Date(step.createdAt).toLocaleTimeString()}
            </Text>
            <Text fontSize="sm" fontWeight="semibold" mt={1}>
              {step.reasoning}
            </Text>
            <Text fontSize="xs" color="gray.600" mt={1}>
              action: <code>{step.actionTaken}</code>
              {step.result ? ` → ${step.result}` : null}
            </Text>
            {stepTools.length > 0 && (
              <Stack gap={1} mt={2} pl={3} borderLeftWidth="2px" borderColor="labs.200">
                {stepTools.map((tc) => (
                  <Box key={tc.id} fontSize="xs">
                    <Badge
                      colorPalette={
                        tc.status === "success"
                          ? "green"
                          : tc.status === "failure" || tc.status === "error"
                          ? "red"
                          : "gray"
                      }
                      mr={2}
                    >
                      {tc.status}
                    </Badge>
                    <code>{tc.toolName}</code>(
                    <Text as="span" color="gray.500">
                      {JSON.stringify(tc.arguments).slice(0, 60)}
                    </Text>
                    )
                    {typeof tc.durationMs === "number" ? ` · ${tc.durationMs}ms` : null}
                  </Box>
                ))}
              </Stack>
            )}
          </Box>
        );
      })}
    </Stack>
  );
}
