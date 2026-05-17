import type { Metadata } from "next";
import { Providers } from "./providers";

export const metadata: Metadata = {
  title: "spool — Strands × Neo4j Agent Memory",
  description:
    "Full-stack demo: an AWS Strands agent backed by Neo4j Agent Memory Service.",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en" suppressHydrationWarning>
      <body style={{ margin: 0, fontFamily: "system-ui, sans-serif" }}>
        <Providers>{children}</Providers>
      </body>
    </html>
  );
}
