/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,
  // Strands ships ESM with deep node imports — let Next.js transpile rather
  // than bundling, so the API route runs Strands in the Node runtime.
  experimental: {
    serverComponentsExternalPackages: ["@strands-agents/sdk"],
  },
};

export default nextConfig;
