import { loadConfig } from "./config";
import { History } from "./history";
import { qwenOmni } from "./providers/qwen-omni";
import { createServer } from "./server";

const config = loadConfig();
const history = new History(config.dbPath);
const provider = qwenOmni(config.qwen);
const server = createServer({ config, provider, history, log: (msg) => console.log(new Date().toISOString(), msg) });

console.log(`pood-server listening on http://${server.hostname}:${server.port} (model ${provider.model})`);

for (const signal of ["SIGINT", "SIGTERM"] as const)
  process.on(signal, () => {
    server.stop(true);
    history.close();
    process.exit(0);
  });
