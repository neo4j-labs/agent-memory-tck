import { readFileSync } from "node:fs";

const pkg = JSON.parse(readFileSync(new URL("../package.json", import.meta.url), "utf8"));
const versionSource = readFileSync(new URL("../src/version.ts", import.meta.url), "utf8");
const match = versionSource.match(/export const VERSION = "([^"]+)";/);

if (!match) {
  console.error("Unable to find VERSION in src/version.ts");
  process.exit(1);
}

if (match[1] !== pkg.version) {
  console.error(
    `src/version.ts (${match[1]}) does not match package.json (${pkg.version}).`,
  );
  process.exit(1);
}
