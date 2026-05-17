"use client";

import { useState, useRef, useEffect } from "react";
import {
  Box,
  Button,
  Flex,
  Heading,
  Input,
  Stack,
  Text,
} from "@chakra-ui/react";

export interface ChatMessage {
  id: string;
  role: "user" | "assistant" | "tool";
  content: string;
}

export interface ToolCallEvent {
  name: string;
  input: Record<string, unknown>;
  result?: string;
}

interface ChatProps {
  conversationId: string | null;
  onConversationId: (id: string) => void;
  onTurnComplete: () => void;
}

export function Chat({ conversationId, onConversationId, onTurnComplete }: ChatProps) {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [input, setInput] = useState("");
  const [busy, setBusy] = useState(false);
  const scroller = useRef<HTMLDivElement>(null);

  useEffect(() => {
    scroller.current?.scrollTo({ top: scroller.current.scrollHeight });
  }, [messages]);

  async function send() {
    const trimmed = input.trim();
    if (!trimmed || busy) return;
    setInput("");
    setBusy(true);

    const userMsgId = `u-${Date.now()}`;
    setMessages((m) => [...m, { id: userMsgId, role: "user", content: trimmed }]);

    const assistantId = `a-${Date.now()}`;
    setMessages((m) => [...m, { id: assistantId, role: "assistant", content: "" }]);

    try {
      const res = await fetch("/api/chat", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ message: trimmed, conversationId }),
      });
      if (!res.body) throw new Error("no response body");
      const reader = res.body.getReader();
      const decoder = new TextDecoder();
      let buffered = "";
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buffered += decoder.decode(value, { stream: true });
        const lines = buffered.split("\n");
        buffered = lines.pop() ?? "";
        for (const line of lines) {
          if (!line.trim()) continue;
          const event = JSON.parse(line) as { type: string; data: unknown };
          if (event.type === "conversation") {
            onConversationId((event.data as { id: string }).id);
          } else if (event.type === "text") {
            const chunk = event.data as string;
            setMessages((prev) =>
              prev.map((m) =>
                m.id === assistantId ? { ...m, content: m.content + chunk } : m,
                ),
            );
          } else if (event.type === "toolCall") {
            const toolCall = event.data as ToolCallEvent;
            setMessages((prev) => [
              ...prev,
              {
                id: `t-${Date.now()}-${prev.length}`,
                role: "tool",
                content: formatToolCall(toolCall),
              },
            ]);
          } else if (event.type === "done") {
            onTurnComplete();
          } else if (event.type === "error") {
            const err = event.data as { message: string };
            setMessages((prev) =>
              prev.map((m) =>
                m.id === assistantId
                  ? { ...m, content: `[error] ${err.message}` }
                  : m,
              ),
            );
          }
        }
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <Flex direction="column" h="100%">
      <Box p={4} borderBottomWidth="1px">
        <Heading size="md" color="labs.600">
          🧶 spool
        </Heading>
        <Text fontSize="sm" color="gray.500">
          Strands agent × Neo4j Agent Memory
        </Text>
      </Box>

      <Box ref={scroller} flex="1" overflowY="auto" p={4}>
        {messages.length === 0 ? (
          <Text color="gray.400" mt={6} textAlign="center">
            Say hi to start a conversation.
          </Text>
        ) : (
          <Stack gap={3}>
            {messages.map((m) => (
              <Bubble key={m.id} role={m.role}>
                {m.content || (m.role === "assistant" ? "…" : "")}
              </Bubble>
            ))}
          </Stack>
        )}
      </Box>

      <Flex p={4} gap={2} borderTopWidth="1px">
        <Input
          value={input}
          placeholder="Ask about graphs, memory, or anything else."
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter" && !e.shiftKey) {
              e.preventDefault();
              void send();
            }
          }}
          disabled={busy}
        />
        <Button onClick={() => void send()} disabled={busy} bg="labs.500" color="white" _hover={{ bg: "labs.600" }}>
          Send
        </Button>
      </Flex>
    </Flex>
  );
}

function formatToolCall(toolCall: ToolCallEvent): string {
  const input =
    toolCall.input && Object.keys(toolCall.input).length > 0
      ? ` ${JSON.stringify(toolCall.input)}`
      : "";
  const result = toolCall.result ? `\n→ ${toolCall.result}` : "";
  return `🔧 ${toolCall.name}${input}${result}`;
}

function Bubble({
  role,
  children,
}: {
  role: "user" | "assistant" | "tool";
  children: React.ReactNode;
}) {
  return (
    <Flex justify={role === "user" ? "flex-end" : "flex-start"}>
      <Box
        maxW="80%"
        px={3}
        py={2}
        borderRadius="md"
        bg={role === "user" ? "labs.500" : role === "tool" ? "orange.50" : "gray.100"}
        color={role === "user" ? "white" : role === "tool" ? "orange.700" : "gray.800"}
        borderWidth={role === "tool" ? "1px" : undefined}
        borderColor={role === "tool" ? "orange.200" : undefined}
      >
        <Text whiteSpace="pre-wrap" fontSize="sm">
          {children}
        </Text>
      </Box>
    </Flex>
  );
}
