const SCHEMA = [
  `CREATE TABLE IF NOT EXISTS app_meta (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
  )`,
  `CREATE TABLE IF NOT EXISTS tasks (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    title TEXT NOT NULL,
    details TEXT,
    status TEXT NOT NULL DEFAULT 'open',
    due_at TEXT,
    project_id INTEGER,
    client_id INTEGER,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
  )`,
  `CREATE TABLE IF NOT EXISTS clients (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    phone TEXT,
    messenger TEXT,
    project TEXT,
    notes TEXT,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
  )`,
  `CREATE TABLE IF NOT EXISTS projects (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL UNIQUE,
    status TEXT NOT NULL DEFAULT 'active',
    notes TEXT,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
  )`,
  `CREATE TABLE IF NOT EXISTS ideas (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    text TEXT NOT NULL,
    project_id INTEGER,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
  )`,
  `CREATE TABLE IF NOT EXISTS conversations (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    role TEXT NOT NULL CHECK(role IN ('user','assistant','system')),
    content TEXT NOT NULL,
    project_id INTEGER,
    client_id INTEGER,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
  )`,
  `CREATE TABLE IF NOT EXISTS actions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    action_type TEXT NOT NULL,
    target TEXT,
    payload TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'pending',
    confirmed_at TEXT,
    executed_at TEXT,
    error TEXT,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
  )`
];

async function ensureSchema(DB) {
  if (!DB) return { ok:false, error:"DB binding missing" };
  for (const sql of SCHEMA) await DB.prepare(sql).run();
  await DB.prepare(
    "INSERT OR REPLACE INTO app_meta (key,value,updated_at) VALUES ('schema_version','0.6',CURRENT_TIMESTAMP)"
  ).run();
  return { ok:true, schema:"0.6" };
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (url.pathname === "/api/health") {
      try {
        const db = await ensureSchema(env.DB);
        return Response.json({
          ok: db.ok,
          app: "NEUROGRAF WORK AI",
          assistant: "Мира",
          version: "0.6-db",
          database: db.ok ? "ready" : "missing",
          schema: db.schema || null,
          error: db.error || null
        }, { status: db.ok ? 200 : 503 });
      } catch (e) {
        return Response.json({
          ok:false,
          app:"NEUROGRAF WORK AI",
          assistant:"Мира",
          version:"0.6-db",
          database:"error",
          error:String(e?.message || e)
        }, { status:500 });
      }
    }

    return env.ASSETS.fetch(request);
  }
};
