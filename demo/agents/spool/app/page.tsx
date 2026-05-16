"use client";

import { useState } from "react";
import { Box, Flex } from "@chakra-ui/react";
import { Chat } from "@/components/Chat";
import { SidePanel } from "@/components/SidePanel";

export default function Home() {
  const [conversationId, setConversationId] = useState<string | null>(null);
  const [refreshKey, setRefreshKey] = useState(0);

  return (
    <Flex h="100vh" w="100vw">
      <Box flex="1" borderRightWidth="1px">
        <Chat
          conversationId={conversationId}
          onConversationId={(id) => setConversationId(id)}
          onTurnComplete={() => setRefreshKey((k) => k + 1)}
        />
      </Box>
      <Box w="420px">
        <SidePanel
          conversationId={conversationId}
          onResume={(id) => {
            setConversationId(id);
            setRefreshKey((k) => k + 1);
          }}
          refreshKey={refreshKey}
        />
      </Box>
    </Flex>
  );
}
