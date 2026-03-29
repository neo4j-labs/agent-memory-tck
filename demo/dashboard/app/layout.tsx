import type { Metadata } from "next";

export const metadata: Metadata = {
  title: "Agent Memory Dashboard",
  description: "Real-time visualization of multi-agent shared memory",
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="en">
      <body style={{ margin: 0, fontFamily: "system-ui, sans-serif", background: "#0a0a0a", color: "#fafafa" }}>
        {children}
      </body>
    </html>
  );
}
