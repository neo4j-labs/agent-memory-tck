"use client";

import { createSystem, defaultConfig } from "@chakra-ui/react";

/**
 * Spool theme — Neo4j Labs purple accent with Chakra's default neutrals.
 * Mirrors the existing demo/dashboard look so the two demos feel like
 * siblings.
 */
export const system = createSystem(defaultConfig, {
  theme: {
    tokens: {
      colors: {
        labs: {
          50: { value: "#EEEBFF" },
          100: { value: "#D4CCFB" },
          400: { value: "#A5B4FC" },
          500: { value: "#6366F1" },
          600: { value: "#4F46E5" },
          700: { value: "#4338CA" },
        },
        neo4j: {
          teal: { value: "#009999" },
        },
      },
    },
    semanticTokens: {
      colors: {
        labsAccent: { value: "{colors.labs.500}" },
        labsAccentHover: { value: "{colors.labs.600}" },
      },
    },
  },
});
