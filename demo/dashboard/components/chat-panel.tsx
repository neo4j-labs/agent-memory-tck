"use client";

import { useState, useRef, useEffect } from "react";
import {
  Box,
  Button,
  HStack,
  Heading,
  NativeSelect,
  Spinner,
  Stack,
  Text,
  Textarea,
} from "@chakra-ui/react";
import { LuSend } from "react-icons/lu";
import type { AgentStatus } from "./agent-panel";

interface ChatMessage {
  id: string;
  role: "user" | "assistant";
  agent?: string;
  content: string;
}

interface ChatPanelProps {
  agents: AgentStatus[];
  onGraphUpdate: () => void;
}

const AGENT_COLORS: Record<string, string> = {
  Lenny: "#3b82f6",
  Scout: "#22c55e",
  Forge: "#f97316",
  Atlas: "#8b5cf6",
  Sage: "#ec4899",
  Rune: "#2F9E44",
};

export function ChatPanel({ agents, onGraphUpdate }: ChatPanelProps) {
  const [selectedAgent, setSelectedAgent] = useState("");
  const [input, setInput] = useState("");
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [loading, setLoading] = useState(false);
  const scrollRef = useRef<HTMLDivElement>(null);

  const healthyAgents = agents.filter((a) => a.healthy);

  useEffect(() => {
    if (healthyAgents.length > 0 && !selectedAgent) {
      setSelectedAgent(healthyAgents[0].name);
    }
  }, [healthyAgents, selectedAgent]);

  useEffect(() => {
    scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight, behavior: "smooth" });
  }, [messages]);

  const sendMessage = async () => {
    if (!input.trim() || !selectedAgent || loading) return;

    const userMsg: ChatMessage = {
      id: `user-${Date.now()}`,
      role: "user",
      content: input.trim(),
    };
    setMessages((prev) => [...prev, userMsg]);
    setInput("");
    setLoading(true);

    try {
      const res = await fetch("/api/chat", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ agent: selectedAgent, message: userMsg.content }),
      });

      const data = await res.json();

      const assistantMsg: ChatMessage = {
        id: `assistant-${Date.now()}`,
        role: "assistant",
        agent: selectedAgent,
        content: data.response ?? data.error ?? "No response",
      };
      setMessages((prev) => [...prev, assistantMsg]);

      setTimeout(onGraphUpdate, 1000);
    } catch (err) {
      const errorMsg: ChatMessage = {
        id: `error-${Date.now()}`,
        role: "assistant",
        agent: selectedAgent,
        content: `Error: ${err instanceof Error ? err.message : "Failed to reach agent"}`,
      };
      setMessages((prev) => [...prev, errorMsg]);
    } finally {
      setLoading(false);
    }
  };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      sendMessage();
    }
  };

  return (
    <Stack h="100%" gap="0">
      <Box px="3" py="2" borderBottomWidth="1px" borderColor="border.subtle">
        <Heading size="xs" color="fg.muted" mb="2" textTransform="uppercase" letterSpacing="wide">
          Chat with Agent
        </Heading>
        <NativeSelect.Root size="sm">
          <NativeSelect.Field
            value={selectedAgent}
            onChange={(e) => setSelectedAgent(e.target.value)}
          >
            {healthyAgents.length === 0 && (
              <option value="">No agents available</option>
            )}
            {healthyAgents.map((a) => (
              <option key={a.name} value={a.name}>
                {a.name} ({a.language}/{a.framework})
              </option>
            ))}
          </NativeSelect.Field>
        </NativeSelect.Root>
      </Box>

      <Box ref={scrollRef} flex="1" overflowY="auto" px="3" py="2">
        <Stack gap="3">
          {messages.length === 0 && (
            <Text textStyle="xs" color="fg.subtle" textAlign="center" mt="8">
              Select an agent and send a message to see the graph update in
              real time.
            </Text>
          )}
          {messages.map((msg) => (
            <Box
              key={msg.id}
              alignSelf={msg.role === "user" ? "flex-end" : "flex-start"}
              maxW="90%"
              bg={msg.role === "user" ? "blue.subtle" : "bg.subtle"}
              borderWidth="1px"
              borderColor={msg.role === "user" ? "blue.border" : "border.subtle"}
              px="3"
              py="2"
              rounded="lg"
            >
              {msg.role === "assistant" && msg.agent && (
                <Text
                  textStyle="2xs"
                  fontWeight="bold"
                  color={AGENT_COLORS[msg.agent] ?? "fg.muted"}
                  mb="1"
                >
                  {msg.agent}
                </Text>
              )}
              <Text textStyle="xs" whiteSpace="pre-wrap">
                {msg.content}
              </Text>
            </Box>
          ))}
          {loading && (
            <HStack gap="2" color="fg.muted">
              <Spinner size="xs" />
              <Text textStyle="xs">
                {selectedAgent} is thinking…
              </Text>
            </HStack>
          )}
        </Stack>
      </Box>

      <Box px="3" py="2" borderTopWidth="1px" borderColor="border.subtle">
        <HStack gap="2">
          <Textarea
            size="sm"
            rows={2}
            placeholder={
              selectedAgent
                ? `Message ${selectedAgent}…`
                : "Select an agent first"
            }
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={handleKeyDown}
            disabled={!selectedAgent || loading}
            resize="none"
          />
          <Button
            size="sm"
            colorPalette="blue"
            onClick={sendMessage}
            disabled={!input.trim() || !selectedAgent || loading}
          >
            <LuSend />
          </Button>
        </HStack>
      </Box>
    </Stack>
  );
}
