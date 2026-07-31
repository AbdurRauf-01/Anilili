import { cloudflareTest } from "@cloudflare/vitest-pool-workers";
import { defineConfig } from "vitest/config";

export default defineConfig({
  plugins: [
    cloudflareTest({
      wrangler: { configPath: "./wrangler.jsonc" },
      miniflare: {
        bindings: {
          ADMIN_ACCESS_KEY: "worker-test-admin-access-key-1234567890",
          RATE_LIMIT_SECRET: "worker-test-rate-limit-secret-1234567890",
          HF_S3_ACCESS_KEY_ID: "HFAKTESTACCESSKEY",
          HF_S3_SECRET_ACCESS_KEY: "worker-test-hf-secret-access-key",
        },
      },
    }),
  ],
  test: {
    include: ["cloudflare/**/*.worker.test.ts"],
  },
});
