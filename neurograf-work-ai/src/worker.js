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

const SYSTEM = `
Ты — Мира, персональный рабочий AI-ассистент в приложении NEUROGRAF WORK AI.
Всегда отвечай на русском языке.
Манера: мягкая, спокойная, доброжелательная, деловая, без тараторки и лишней воды.
Помогай с повседневной работой: задачами, клиентами, проектами, идеями, сообщениями, постами, рекламой, промтами и планированием.
Используй переданный контекст приложения. Не выдумывай отсутствующие факты.
Если пользователь просит отправить сообщение, опубликовать что-либо, удалить данные или выполнить другое внешнее/необратимое действие, подготовь результат, но не утверждай, что действие уже выполнено. Такие действия требуют отдельного подтверждения и реального серверного действия.
Если ответ предназначен для чтения вслух, пиши естественно, короткими фразами.
`;

function j(data, status=200, extra={}) {
  return new Response(JSON.stringify(data), {
    status,
    headers: {"Content-Type":"application/json; charset=utf-8","Cache-Control":"no-store",...extra}
  });
}

async function ensureSchema(DB) {
  if (!DB) return {ok:false,error:"DB binding missing"};
  for (const sql of SCHEMA) await DB.prepare(sql).run();
  await DB.prepare("INSERT OR REPLACE INTO app_meta (key,value,updated_at) VALUES ('schema_version','0.7',CURRENT_TIMESTAMP)").run();
  return {ok:true,schema:"0.7"};
}

function b64url(bytes) {
  let s="";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g,"-").replace(/\//g,"_").replace(/=+$/,"");
}
function utf8(s){ return new TextEncoder().encode(s); }

async function hmac(secret, text) {
  const key = await crypto.subtle.importKey("raw", utf8(secret), {name:"HMAC",hash:"SHA-256"}, false, ["sign"]);
  const sig = await crypto.subtle.sign("HMAC", key, utf8(text));
  return b64url(new Uint8Array(sig));
}

function cookieMap(request) {
  const out={};
  for (const part of (request.headers.get("Cookie")||"").split(";")) {
    const i=part.indexOf("=");
    if(i>0) out[part.slice(0,i).trim()]=part.slice(i+1).trim();
  }
  return out;
}

async function makeSession(env) {
  const payload = b64url(utf8(JSON.stringify({sub:"owner",exp:Date.now()+1000*60*60*24*30})));
  const sig = await hmac(env.SESSION_SECRET, payload);
  return payload+"."+sig;
}

async function validSession(request, env) {
  if(!env.SESSION_SECRET) return false;
  const token=cookieMap(request).mira_session;
  if(!token || !token.includes(".")) return false;
  const [payload,sig]=token.split(".");
  const expected=await hmac(env.SESSION_SECRET,payload);
  if(sig!==expected) return false;
  try{
    const json=JSON.parse(new TextDecoder().decode(Uint8Array.from(atob(payload.replace(/-/g,"+").replace(/_/g,"/")),c=>c.charCodeAt(0))));
    return json.sub==="owner" && Number(json.exp)>Date.now();
  }catch{return false;}
}

async function sameSecret(a,b) {
  const [ha,hb]=await Promise.all([crypto.subtle.digest("SHA-256",utf8(a||"")),crypto.subtle.digest("SHA-256",utf8(b||""))]);
  const A=new Uint8Array(ha), B=new Uint8Array(hb);
  let diff=A.length^B.length;
  for(let i=0;i<Math.min(A.length,B.length);i++) diff|=A[i]^B[i];
  return diff===0;
}

async function bodyJson(request){
  try{return await request.json();}catch{return {};}
}

function extractText(data){
  if(typeof data?.output_text==="string" && data.output_text.trim()) return data.output_text.trim();
  for(const item of (Array.isArray(data?.output)?data.output:[])){
    for(const c of (Array.isArray(item?.content)?item.content:[])){
      if(typeof c?.text==="string" && c.text.trim()) return c.text.trim();
    }
  }
  return "";
}

async function appContext(DB){
  const [tasks,clients,projects,ideas,history] = await Promise.all([
    DB.prepare("SELECT id,title,details,status,due_at,project_id,client_id FROM tasks ORDER BY CASE WHEN status='open' THEN 0 ELSE 1 END, id DESC LIMIT 30").all(),
    DB.prepare("SELECT id,name,phone,messenger,project,notes FROM clients ORDER BY id DESC LIMIT 30").all(),
    DB.prepare("SELECT id,name,status,notes FROM projects ORDER BY id DESC LIMIT 30").all(),
    DB.prepare("SELECT id,text,project_id,created_at FROM ideas ORDER BY id DESC LIMIT 20").all(),
    DB.prepare("SELECT role,content,created_at FROM conversations ORDER BY id DESC LIMIT 12").all()
  ]);
  return {tasks:tasks.results||[],clients:clients.results||[],projects:projects.results||[],ideas:ideas.results||[],history:(history.results||[]).reverse()};
}

async function routeApi(request, env, url){
  const schema=await ensureSchema(env.DB);

  if(url.pathname==="/api/health"){
    return j({
      ok:schema.ok,
      app:"NEUROGRAF WORK AI",
      assistant:"Мира",
      version:"0.7-ai",
      database:schema.ok?"ready":"missing",
      schema:schema.schema||null,
      owner_password:Boolean(env.OWNER_PASSWORD),
      session_secret:Boolean(env.SESSION_SECRET),
      openai:Boolean(env.OPENAI_API_KEY)
    }, schema.ok?200:503);
  }

  // Login endpoint sets an HttpOnly session cookie.
  if(url.pathname==="/api/login-cookie" && request.method==="POST"){
    if(!env.OWNER_PASSWORD || !env.SESSION_SECRET) return j({error:"Owner auth is not configured"},503);
    const body=await bodyJson(request);
    if(!(await sameSecret(String(body.password||""),env.OWNER_PASSWORD))) return j({error:"Неверный пароль"},401);
    const token=await makeSession(env);
    return new Response(JSON.stringify({ok:true}),{
      status:200,
      headers:{
        "Content-Type":"application/json; charset=utf-8",
        "Cache-Control":"no-store",
        "Set-Cookie":`mira_session=${token}; Path=/; Max-Age=2592000; HttpOnly; Secure; SameSite=Strict`
      }
    });
  }

  if(url.pathname==="/api/logout" && request.method==="POST"){
    return new Response(JSON.stringify({ok:true}),{
      headers:{
        "Content-Type":"application/json; charset=utf-8",
        "Set-Cookie":"mira_session=; Path=/; Max-Age=0; HttpOnly; Secure; SameSite=Strict"
      }
    });
  }

  const authed=await validSession(request,env);
  if(url.pathname==="/api/session") return j({authenticated:authed});
  if(!authed) return j({error:"AUTH_REQUIRED"},401);

  if(url.pathname==="/api/bootstrap" && request.method==="GET"){
    return j(await appContext(env.DB));
  }

  if(url.pathname==="/api/tasks" && request.method==="POST"){
    const b=await bodyJson(request);
    const title=String(b.title||"").trim().slice(0,500);
    if(!title) return j({error:"title required"},400);
    const r=await env.DB.prepare("INSERT INTO tasks(title,details,due_at,project_id,client_id) VALUES(?,?,?,?,?)")
      .bind(title,String(b.details||"").slice(0,4000)||null,b.due_at||null,b.project_id||null,b.client_id||null).run();
    return j({ok:true,id:r.meta?.last_row_id||null});
  }

  const taskMatch=url.pathname.match(/^\/api\/tasks\/(\d+)$/);
  if(taskMatch && request.method==="PATCH"){
    const b=await bodyJson(request);
    const status=b.status==="done"?"done":"open";
    await env.DB.prepare("UPDATE tasks SET status=?,updated_at=CURRENT_TIMESTAMP WHERE id=?").bind(status,Number(taskMatch[1])).run();
    return j({ok:true});
  }

  if(url.pathname==="/api/clients" && request.method==="POST"){
    const b=await bodyJson(request);
    const name=String(b.name||"").trim().slice(0,300);
    if(!name) return j({error:"name required"},400);
    const r=await env.DB.prepare("INSERT INTO clients(name,phone,messenger,project,notes) VALUES(?,?,?,?,?)")
      .bind(name,String(b.phone||"").slice(0,100)||null,String(b.messenger||"").slice(0,100)||null,String(b.project||"").slice(0,300)||null,String(b.notes||"").slice(0,4000)||null).run();
    return j({ok:true,id:r.meta?.last_row_id||null});
  }

  if(url.pathname==="/api/projects" && request.method==="POST"){
    const b=await bodyJson(request);
    const name=String(b.name||"").trim().slice(0,300);
    if(!name) return j({error:"name required"},400);
    try{
      const r=await env.DB.prepare("INSERT INTO projects(name,notes) VALUES(?,?)").bind(name,String(b.notes||"").slice(0,4000)||null).run();
      return j({ok:true,id:r.meta?.last_row_id||null});
    }catch(e){return j({error:"Проект уже существует или данные некорректны"},409);}
  }

  if(url.pathname==="/api/ideas" && request.method==="POST"){
    const b=await bodyJson(request);
    const text=String(b.text||"").trim().slice(0,6000);
    if(!text) return j({error:"text required"},400);
    const r=await env.DB.prepare("INSERT INTO ideas(text,project_id) VALUES(?,?)").bind(text,b.project_id||null).run();
    return j({ok:true,id:r.meta?.last_row_id||null});
  }

  if(url.pathname==="/api/chat" && request.method==="POST"){
    if(!env.OPENAI_API_KEY) return j({error:"OPENAI_API_KEY not configured"},503);
    const b=await bodyJson(request);
    const message=String(b.message||"").trim().slice(0,12000);
    if(!message) return j({error:"message required"},400);

    await env.DB.prepare("INSERT INTO conversations(role,content) VALUES('user',?)").bind(message).run();
    const ctx=await appContext(env.DB);

    const r=await fetch("https://api.openai.com/v1/responses",{
      method:"POST",
      headers:{"Authorization":`Bearer ${env.OPENAI_API_KEY}`,"Content-Type":"application/json"},
      body:JSON.stringify({
        model:env.OPENAI_CHAT_MODEL||"gpt-5.6-luna",
        instructions:SYSTEM,
        input:`Контекст приложения:\n${JSON.stringify(ctx)}\n\nЗапрос пользователя:\n${message}`
      })
    });
    const data=await r.json().catch(()=>({}));
    if(!r.ok) return j({error:"OpenAI error",detail:data?.error?.message||"unknown"},502);
    const answer=extractText(data)||"Не удалось получить текст ответа.";
    await env.DB.prepare("INSERT INTO conversations(role,content) VALUES('assistant',?)").bind(answer).run();
    return j({answer});
  }

  if(url.pathname==="/api/tts" && request.method==="POST"){
    if(!env.OPENAI_API_KEY) return j({error:"OPENAI_API_KEY not configured"},503);
    const b=await bodyJson(request);
    const text=String(b.text||"").trim().slice(0,3500);
    if(!text) return j({error:"text required"},400);
    const r=await fetch("https://api.openai.com/v1/audio/speech",{
      method:"POST",
      headers:{"Authorization":`Bearer ${env.OPENAI_API_KEY}`,"Content-Type":"application/json"},
      body:JSON.stringify({
        model:env.OPENAI_TTS_MODEL||"gpt-4o-mini-tts",
        voice:env.OPENAI_TTS_VOICE||"coral",
        input:text,
        instructions:"Говори по-русски. Женский мягкий, спокойный, естественный голос. Темп немного медленнее среднего. Тепло и уверенно, без рекламной интонации и без тараторки.",
        response_format:"mp3"
      })
    });
    if(!r.ok){
      const detail=await r.text();
      return j({error:"TTS error",detail:detail.slice(0,800)},502);
    }
    return new Response(r.body,{headers:{"Content-Type":"audio/mpeg","Cache-Control":"no-store"}});
  }

  return j({error:"Not found"},404);
}

export default {
  async fetch(request,env){
    const url=new URL(request.url);
    if(url.pathname.startsWith("/api/")) return routeApi(request,env,url);
    return env.ASSETS.fetch(request);
  }
};