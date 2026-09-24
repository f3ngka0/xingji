import { createServer } from "node:http";
import { createApp } from "./app";
import { readConfig } from "./config";
import { openDatabase } from "./database";
import { closeExpiredTrips, removeExpiredData } from "./domain";

const config = readConfig();
const db = openDatabase(config.dbPath);
const app = createApp(db, config);

function maintenance(): void {
  try {
    closeExpiredTrips(db);
    removeExpiredData(db, Date.now(), config.dataRetentionDays);
  } catch {
    // Avoid printing trip identifiers, tokens, or database values into server logs.
    process.stderr.write("Scheduled trip maintenance failed.\n");
  }
}

maintenance();
const maintenanceTimer = setInterval(maintenance, 60_000);
maintenanceTimer.unref();

const server = createServer(app);
server.listen(config.port, config.host, () => {
  process.stdout.write(`Trip sharing API listening on ${config.host}:${config.port}\n`);
});

function stop(): void {
  clearInterval(maintenanceTimer);
  server.close(() => {
    db.close();
    process.exit(0);
  });
  setTimeout(() => process.exit(1), 10_000).unref();
}

process.once("SIGINT", stop);
process.once("SIGTERM", stop);
