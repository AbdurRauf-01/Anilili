import { createApp } from "./app.js";
import { runtimeConfig } from "./config.js";
import { createStoreFromEnvironment } from "./store.js";

const port = Number(process.env.PORT ?? 8080);
const app = createApp({ store: createStoreFromEnvironment(), config: runtimeConfig() });

app.listen(port, "0.0.0.0", () => {
  console.log(JSON.stringify({ timestamp: new Date().toISOString(), name: "server.started", port }));
});
