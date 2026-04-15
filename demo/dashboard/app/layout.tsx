import type { Metadata } from "next";
import { Provider } from "@/components/ui/provider";

export const metadata: Metadata = {
  title: "Agent Memory — Polyglot Demo",
  description:
    "Real-time visualization of multi-agent shared memory backed by Neo4j",
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="en" suppressHydrationWarning>
      <body>
        <Provider forcedTheme="dark">{children}</Provider>
      </body>
    </html>
  );
}
