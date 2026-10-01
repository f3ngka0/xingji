import Database from "better-sqlite3";
import { readFileSync } from "node:fs";
import { mkdirSync } from "node:fs";
import { dirname, join } from "node:path";

export function openDatabase(path: string): Database.Database {
  if (path !== ":memory:") mkdirSync(dirname(path), { recursive: true });
  const db = new Database(path);
  db.pragma("foreign_keys = ON");
  db.pragma("busy_timeout = 5000");
  if (path !== ":memory:") db.pragma("journal_mode = WAL");
  migrate(db);
  return db;
}

export function migrate(db: Database.Database): void {
  const version = Number(db.pragma("user_version", { simple: true }));
  if (version < 1) {
    const sql = readFileSync(join(__dirname, "..", "migrations", "001_init.sql"), "utf8");
    db.transaction(() => {
      db.exec(sql);
      db.pragma("user_version = 1");
    })();
  }
  const currentVersion = Number(db.pragma("user_version", { simple: true }));
  if (currentVersion < 2) {
    const sql = readFileSync(join(__dirname, "..", "migrations", "002_encrypt_share_tokens.sql"), "utf8");
    db.transaction(() => {
      db.exec(sql);
      db.pragma("user_version = 2");
    })();
  }
  const versionAfterEncryption = Number(db.pragma("user_version", { simple: true }));
  if (versionAfterEncryption < 3) {
    const sql = readFileSync(join(__dirname, "..", "migrations", "003_position_place_label.sql"), "utf8");
    db.transaction(() => {
      db.exec(sql);
      db.pragma("user_version = 3");
    })();
  }
  const versionAfterPlaceLabel = Number(db.pragma("user_version", { simple: true }));
  if (versionAfterPlaceLabel < 4) {
    const sql = readFileSync(join(__dirname, "..", "migrations", "004_trip_map_provider.sql"), "utf8");
    db.transaction(() => {
      db.exec(sql);
      db.pragma("user_version = 4");
    })();
  }
}
