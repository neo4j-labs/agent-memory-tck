import type { Metadata } from "next";

export const metadata: Metadata = {
  title: "Agent Memory — Polyglot Demo",
  description: "Real-time visualization of multi-agent shared memory backed by Neo4j",
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="en">
      <body style={{
        margin: 0,
        fontFamily: "system-ui, -apple-system, 'Segoe UI', sans-serif",
        background: "#0a0a0a",
        color: "#e2e8f0",
        WebkitFontSmoothing: "antialiased",
      }}>
        {children}
      </body>
    </html>
  );
}
