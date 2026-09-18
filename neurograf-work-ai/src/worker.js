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
  )`,
  `CREATE TABLE IF NOT EXISTS max_contacts (
    user_id TEXT PRIMARY KEY,
    first_name TEXT,
    last_name TEXT,
    username TEXT,
    chat_id TEXT,
    client_id INTEGER,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
  )`,
  `CREATE TABLE IF NOT EXISTS memories (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    category TEXT NOT NULL DEFAULT 'preference',
    content TEXT NOT NULL,
    importance INTEGER NOT NULL DEFAULT 3,
    active INTEGER NOT NULL DEFAULT 1,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
  )`,
  `CREATE TABLE IF NOT EXISTS knowledge (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    title TEXT NOT NULL,
    content TEXT NOT NULL,
    source TEXT,
    active INTEGER NOT NULL DEFAULT 1,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
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
Если пользователь просит создать задачу, сохранить идею, добавить клиента или проект — обязательно используй соответствующий инструмент, а не только отвечай текстом.
Если пользователь спрашивает о задачах или просит найти клиента — используй инструмент, чтобы получить актуальные данные.
Если пользователь просит отправить сообщение во внешний сервис, разрешено только подготовить черновик через draft_message. Никогда не говори, что черновик отправлен.
Отвечай обычным чистым текстом без Markdown-разметки: не используй символы >, **, __, заголовки # и тройные обратные кавычки.
Постоянные записи из блока memory считай устойчивыми предпочтениями и правилами владельца и применяй их автоматически, когда они относятся к текущей задаче.
Материалы из блока knowledge используй как рабочую базу знаний. Если знания противоречат свежим данным пользователя, приоритет у свежего сообщения пользователя.
Если пользователь говорит «запомни», «всегда делай», «мне нравится», «я предпочитаю» или явно формулирует постоянное правило — используй инструмент save_memory.
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
  await DB.prepare("INSERT OR REPLACE INTO app_meta (key,value,updated_at) VALUES ('schema_version','1.2',CURRENT_TIMESTAMP)").run();
  return {ok:true,schema:"1.2"};
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

function cleanAssistantText(text){
  return String(text||"")
    .replace(/^\s*>\s?/gm,"")
    .replace(/\*\*(.*?)\*\*/g,"$1")
    .replace(/__(.*?)__/g,"$1")
    .replace(/^\s*#{1,6}\s+/gm,"")
    .replace(/^\s*[-*]\s+/gm,"• ")
    .replace(/```[a-zA-Z0-9_-]*\n?/g,"")
    .replace(/```/g,"")
    .replace(/\[([^\]]+)\]\(([^)]+)\)/g,"$1 ($2)")
    .replace(/\n{3,}/g,"\n\n")
    .trim();
}

async function appContext(DB){
  const [tasks,clients,projects,ideas,history,actions,memories,knowledge] = await Promise.all([
    DB.prepare("SELECT id,title,details,status,due_at,project_id,client_id FROM tasks ORDER BY CASE WHEN status='open' THEN 0 ELSE 1 END, id DESC LIMIT 15").all(),
    DB.prepare("SELECT c.id,c.name,c.phone,c.messenger,c.project,c.notes,m.user_id AS max_user_id,m.username AS max_username,m.chat_id AS max_chat_id FROM clients c LEFT JOIN max_contacts m ON m.client_id=c.id ORDER BY c.id DESC LIMIT 15").all(),
    DB.prepare("SELECT id,name,status,notes FROM projects ORDER BY id DESC LIMIT 15").all(),
    DB.prepare("SELECT id,text,project_id,created_at FROM ideas ORDER BY id DESC LIMIT 10").all(),
    DB.prepare("SELECT role,content,created_at FROM conversations ORDER BY id DESC LIMIT 8").all(),
    DB.prepare("SELECT id,action_type,target,payload,status,confirmed_at,created_at FROM actions WHERE status IN ('pending','approved') ORDER BY id DESC LIMIT 10").all(),
    DB.prepare("SELECT id,category,content,importance,created_at FROM memories WHERE active=1 ORDER BY importance DESC,id DESC LIMIT 40").all(),
    DB.prepare("SELECT id,title,content,source,created_at FROM knowledge WHERE active=1 ORDER BY id DESC LIMIT 20").all()
  ]);
  return {
    tasks:tasks.results||[],
    clients:clients.results||[],
    projects:projects.results||[],
    ideas:ideas.results||[],
    history:(history.results||[]).reverse(),
    actions:actions.results||[],
    memory:memories.results||[],
    knowledge:knowledge.results||[]
  };
}


const MIRA_TOOLS = [
  {
    type:"function",
    name:"create_task",
    description:"Создать новую рабочую задачу в приложении. Используй, когда пользователь явно просит добавить, создать, записать или запланировать задачу.",
    parameters:{
      type:"object",
      properties:{
        title:{type:"string",description:"Короткое название задачи"},
        details:{type:"string",description:"Дополнительные детали"},
        due_at:{type:"string",description:"Срок в ISO 8601, если пользователь указал дату или время"},
        client_name:{type:"string",description:"Имя клиента, если задача связана с клиентом"},
        project_name:{type:"string",description:"Название проекта, если задача связана с проектом"}
      },
      required:["title"]
    }
  },
  {
    type:"function",
    name:"save_idea",
    description:"Сохранить идею пользователя в приложении.",
    parameters:{
      type:"object",
      properties:{
        text:{type:"string",description:"Текст идеи"},
        project_name:{type:"string",description:"Название проекта, если идея относится к проекту"}
      },
      required:["text"]
    }
  },
  {
    type:"function",
    name:"list_tasks",
    description:"Получить актуальный список задач пользователя.",
    parameters:{
      type:"object",
      properties:{
        status:{type:"string",enum:["open","done","all"],description:"Какие задачи показать"}
      }
    }
  },
  {
    type:"function",
    name:"find_client",
    description:"Найти клиента по имени, компании, телефону или проекту.",
    parameters:{
      type:"object",
      properties:{query:{type:"string",description:"Что искать"}},
      required:["query"]
    }
  },
  {
    type:"function",
    name:"create_client",
    description:"Добавить нового клиента в приложение.",
    parameters:{
      type:"object",
      properties:{
        name:{type:"string"},
        phone:{type:"string"},
        messenger:{type:"string"},
        project:{type:"string"},
        notes:{type:"string"}
      },
      required:["name"]
    }
  },
  {
    type:"function",
    name:"create_project",
    description:"Создать новый проект.",
    parameters:{
      type:"object",
      properties:{name:{type:"string"},notes:{type:"string"}},
      required:["name"]
    }
  },
  {
    type:"function",
    name:"save_memory",
    description:"Сохранить долговременное правило, предпочтение или важный факт владельца. Используй только для устойчивой информации, которая пригодится в будущих разговорах.",
    parameters:{
      type:"object",
      properties:{
        content:{type:"string",description:"Что именно нужно запомнить"},
        category:{type:"string",enum:["preference","rule","profile","workflow","other"]},
        importance:{type:"integer",minimum:1,maximum:5}
      },
      required:["content"]
    }
  },
  {
    type:"function",
    name:"list_memories",
    description:"Показать активные записи долговременной памяти владельца.",
    parameters:{type:"object",properties:{}}
  },
  {
    type:"function",
    name:"search_knowledge",
    description:"Найти информацию в рабочей базе знаний Миры.",
    parameters:{
      type:"object",
      properties:{query:{type:"string"}},
      required:["query"]
    }
  },
  {
    type:"function",
    name:"draft_message",
    description:"Создать черновик внешнего сообщения. Это НЕ отправка. Используй, если пользователь просит написать или отправить сообщение клиенту, в MAX, WhatsApp, email или другой внешний канал.",
    parameters:{
      type:"object",
      properties:{
        recipient_name:{type:"string",description:"Кому предназначено сообщение"},
        channel:{type:"string",enum:["max","whatsapp","email","sms","other"]},
        text:{type:"string",description:"Готовый текст сообщения"}
      },
      required:["recipient_name","channel","text"]
    }
  }
];

async function openAI(env, payload){
  async function send(body){
    const r=await fetch("https://api.openai.com/v1/responses",{
      method:"POST",
      headers:{"Authorization":"Bearer "+env.OPENAI_API_KEY,"Content-Type":"application/json"},
      body:JSON.stringify(body)
    });
    const data=await r.json().catch(()=>({}));
    return {r,data};
  }

  const primary={...payload};
  if(primary.max_output_tokens==null) primary.max_output_tokens=1600;
  if(primary.reasoning==null) primary.reasoning={effort:"none"};

  let {r,data}=await send(primary);
  let usedModel=primary.model;

  const fallbacks=[];
  if(primary.model==="gpt-5.6-luna") fallbacks.push("gpt-5.6-terra","gpt-5.6-sol");
  else if(primary.model==="gpt-5.6-terra") fallbacks.push("gpt-5.6-sol");

  for(const model of fallbacks){
    if(r.status!==429) break;
    const fallback={...primary,model};
    ({r,data}=await send(fallback));
    usedModel=model;
  }

  if(!r.ok){
    const msg=data?.error?.message||"OpenAI error";
    const err=new Error(msg);
    err.detail=(r.status===429)
      ?"Сейчас достигнут лимит AI-моделей OpenAI. Подождите немного и повторите запрос."
      :msg;
    err.status=r.status;
    throw err;
  }

  data._mira_model=usedModel;
  return data;
}

async function findClientByName(DB,name){
  if(!name) return null;
  const q="%"+String(name).trim().slice(0,200)+"%";
  return await DB.prepare("SELECT id,name,phone,messenger,project,notes FROM clients WHERE lower(name) LIKE lower(?) OR lower(COALESCE(project,'')) LIKE lower(?) ORDER BY id DESC LIMIT 1").bind(q,q).first();
}

async function findProjectByName(DB,name){
  if(!name) return null;
  const q="%"+String(name).trim().slice(0,200)+"%";
  return await DB.prepare("SELECT id,name,status,notes FROM projects WHERE lower(name) LIKE lower(?) ORDER BY id DESC LIMIT 1").bind(q).first();
}

async function executeMiraTool(name,args,env){
  const DB=env.DB;

  if(name==="save_memory"){
    const content=String(args.content||"").trim().slice(0,4000);
    if(!content) return {ok:false,error:"Пустая запись памяти"};
    const category=["preference","rule","profile","workflow","other"].includes(args.category)?args.category:"preference";
    const importance=Math.max(1,Math.min(5,Number(args.importance)||3));
    const existing=await DB.prepare("SELECT id,content FROM memories WHERE active=1 AND lower(content)=lower(?) ORDER BY id DESC LIMIT 1").bind(content).first();
    if(existing) return {ok:true,action:"memory_exists",id:existing.id,content:existing.content,note:"Такая запись уже есть."};
    const r=await DB.prepare("INSERT INTO memories(category,content,importance) VALUES(?,?,?)").bind(category,content,importance).run();
    return {ok:true,action:"memory_saved",id:r.meta?.last_row_id||null,category,importance,content};
  }

  if(name==="list_memories"){
    const res=await DB.prepare("SELECT id,category,content,importance,created_at FROM memories WHERE active=1 ORDER BY importance DESC,id DESC LIMIT 50").all();
    return {ok:true,action:"memories_listed",memories:res.results||[]};
  }

  if(name==="search_knowledge"){
    const q=String(args.query||"").trim().slice(0,300);
    if(!q) return {ok:false,error:"Пустой запрос"};
    const like="%"+q+"%";
    const res=await DB.prepare("SELECT id,title,content,source FROM knowledge WHERE active=1 AND (lower(title) LIKE lower(?) OR lower(content) LIKE lower(?)) ORDER BY id DESC LIMIT 10").bind(like,like).all();
    return {ok:true,action:"knowledge_found",query:q,items:res.results||[]};
  }
  if(name==="create_task"){
    const title=String(args.title||"").trim().slice(0,500);
    if(!title) return {ok:false,error:"Не указано название задачи"};
    const client=await findClientByName(DB,args.client_name);
    const project=await findProjectByName(DB,args.project_name);
    const due=String(args.due_at||"").trim().slice(0,100)||null;
    const details=String(args.details||"").trim().slice(0,4000)||null;

    const existing=await DB.prepare(
      "SELECT id,title,due_at FROM tasks WHERE status='open' AND lower(title)=lower(?) AND COALESCE(due_at,'')=COALESCE(?,'') AND created_at >= datetime('now','-10 minutes') ORDER BY id DESC LIMIT 1"
    ).bind(title,due).first();

    if(existing){
      return {ok:true,action:"task_exists",id:existing.id,title:existing.title,due_at:existing.due_at,note:"Такая задача уже существует, дубль не создан."};
    }

    const r=await DB.prepare("INSERT INTO tasks(title,details,due_at,project_id,client_id) VALUES(?,?,?,?,?)")
      .bind(title,details,due,project?.id||null,client?.id||null).run();
    return {ok:true,action:"task_created",id:r.meta?.last_row_id||null,title,due_at:due,client:client?.name||null,project:project?.name||null};
  }

  if(name==="save_idea"){
    const text=String(args.text||"").trim().slice(0,6000);
    if(!text) return {ok:false,error:"Пустая идея"};
    const project=await findProjectByName(DB,args.project_name);
    const existing=await DB.prepare(
      "SELECT id,text FROM ideas WHERE lower(text)=lower(?) AND COALESCE(project_id,0)=COALESCE(?,0) AND created_at >= datetime('now','-10 minutes') ORDER BY id DESC LIMIT 1"
    ).bind(text,project?.id||null).first();
    if(existing){
      return {ok:true,action:"idea_exists",id:existing.id,text:existing.text,note:"Такая идея уже сохранена, дубль не создан."};
    }
    const r=await DB.prepare("INSERT INTO ideas(text,project_id) VALUES(?,?)").bind(text,project?.id||null).run();
    return {ok:true,action:"idea_saved",id:r.meta?.last_row_id||null,text,project:project?.name||null};
  }

  if(name==="list_tasks"){
    const status=["open","done","all"].includes(args.status)?args.status:"open";
    let res;
    if(status==="all") res=await DB.prepare("SELECT id,title,details,status,due_at FROM tasks ORDER BY id DESC LIMIT 30").all();
    else res=await DB.prepare("SELECT id,title,details,status,due_at FROM tasks WHERE status=? ORDER BY id DESC LIMIT 30").bind(status).all();
    return {ok:true,action:"tasks_listed",status,tasks:res.results||[]};
  }

  if(name==="find_client"){
    const q=String(args.query||"").trim().slice(0,200);
    if(!q) return {ok:false,error:"Пустой запрос"};
    const like="%"+q+"%";
    const res=await DB.prepare("SELECT id,name,phone,messenger,project,notes FROM clients WHERE lower(name) LIKE lower(?) OR phone LIKE ? OR lower(COALESCE(project,'')) LIKE lower(?) ORDER BY id DESC LIMIT 10").bind(like,like,like).all();
    return {ok:true,action:"clients_found",query:q,clients:res.results||[]};
  }

  if(name==="create_client"){
    const clientName=String(args.name||"").trim().slice(0,300);
    if(!clientName) return {ok:false,error:"Не указано имя клиента"};
    const phone=String(args.phone||"").trim().slice(0,100)||null;
    const existing=await DB.prepare(
      "SELECT id,name,phone FROM clients WHERE lower(name)=lower(?) AND COALESCE(phone,'')=COALESCE(?,'') AND created_at >= datetime('now','-10 minutes') ORDER BY id DESC LIMIT 1"
    ).bind(clientName,phone).first();
    if(existing){
      return {ok:true,action:"client_exists",id:existing.id,name:existing.name,phone:existing.phone,note:"Такой клиент уже есть, дубль не создан."};
    }
    const r=await DB.prepare("INSERT INTO clients(name,phone,messenger,project,notes) VALUES(?,?,?,?,?)")
      .bind(
        clientName,
        phone,
        String(args.messenger||"").trim().slice(0,100)||null,
        String(args.project||"").trim().slice(0,300)||null,
        String(args.notes||"").trim().slice(0,4000)||null
      ).run();
    return {ok:true,action:"client_created",id:r.meta?.last_row_id||null,name:clientName};
  }

  if(name==="create_project"){
    const projectName=String(args.name||"").trim().slice(0,300);
    if(!projectName) return {ok:false,error:"Не указано название проекта"};
    try{
      const r=await DB.prepare("INSERT INTO projects(name,notes) VALUES(?,?)")
        .bind(projectName,String(args.notes||"").trim().slice(0,4000)||null).run();
      return {ok:true,action:"project_created",id:r.meta?.last_row_id||null,name:projectName};
    }catch{
      const existing=await findProjectByName(DB,projectName);
      return {ok:true,action:"project_exists",id:existing?.id||null,name:existing?.name||projectName};
    }
  }

  if(name==="draft_message"){
    const recipient=String(args.recipient_name||"").trim().slice(0,300);
    const channel=["max","whatsapp","email","sms","other"].includes(args.channel)?args.channel:"other";
    const text=String(args.text||"").trim().slice(0,4000);
    if(!recipient||!text) return {ok:false,error:"Не хватает получателя или текста"};
    const payload=JSON.stringify({channel,recipient_name:recipient,text});
    const r=await DB.prepare("INSERT INTO actions(action_type,target,payload,status) VALUES('draft_message',?,?,'pending')")
      .bind(recipient,payload).run();
    return {
      ok:true,
      action:"draft_created",
      id:r.meta?.last_row_id||null,
      recipient_name:recipient,
      channel,
      text,
      status:"pending",
      note:"Черновик сохранён, но НЕ отправлен."
    };
  }

  return {ok:false,error:"Неизвестный инструмент"};
}


async function maxApi(env, path, options={}){
  if(!env.MAX_BOT_TOKEN){
    const e=new Error("MAX_BOT_TOKEN not configured");
    e.status=503;
    throw e;
  }
  const r=await fetch("https://platform-api2.max.ru"+path,{
    ...options,
    headers:{
      "Authorization":env.MAX_BOT_TOKEN,
      ...(options.body?{"Content-Type":"application/json"}:{}),
      ...(options.headers||{})
    }
  });
  const data=await r.json().catch(()=>({}));
  if(!r.ok || data?.success===false){
    const e=new Error(data?.message||data?.error||("MAX API HTTP "+r.status));
    e.status=r.status;
    e.detail=data;
    throw e;
  }
  return data;
}

async function saveMaxContact(DB, update){
  const user=update?.user || update?.message?.sender || null;
  if(!user?.user_id) return {saved:false};
  const chatId=update?.chat_id ?? update?.message?.recipient?.chat_id ?? null;
  let clientId=null;
  const payload=String(update?.payload||"");
  const m=payload.match(/^client_(\d+)$/);
  if(m) clientId=Number(m[1]);
  const existing=await DB.prepare("SELECT client_id FROM max_contacts WHERE user_id=?").bind(String(user.user_id)).first();
  if(!clientId) clientId=existing?.client_id||null;
  const sql="INSERT INTO max_contacts(user_id,first_name,last_name,username,chat_id,client_id,created_at,updated_at) " +
    "VALUES(?,?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) " +
    "ON CONFLICT(user_id) DO UPDATE SET first_name=excluded.first_name,last_name=excluded.last_name,username=excluded.username," +
    "chat_id=COALESCE(excluded.chat_id,max_contacts.chat_id),client_id=COALESCE(excluded.client_id,max_contacts.client_id),updated_at=CURRENT_TIMESTAMP";
  await DB.prepare(sql).bind(
    String(user.user_id),
    String(user.first_name||user.name||"").slice(0,200)||null,
    String(user.last_name||"").slice(0,200)||null,
    String(user.username||"").slice(0,200)||null,
    chatId!=null?String(chatId):null,
    clientId
  ).run();
  return {saved:true,user_id:String(user.user_id),client_id:clientId,chat_id:chatId!=null?String(chatId):null};
}

async function maxBotInfo(env){
  return await maxApi(env,"/me",{method:"GET"});
}

async function routeApi(request, env, url){
  const schema=await ensureSchema(env.DB);

  if(url.pathname==="/api/max/webhook" && request.method==="POST"){
    if(env.MAX_WEBHOOK_SECRET){
      const got=request.headers.get("X-Max-Bot-Api-Secret")||"";
      if(!(await sameSecret(got,env.MAX_WEBHOOK_SECRET))) return j({error:"Invalid MAX webhook secret"},401);
    }
    const update=await bodyJson(request);
    const saved=await saveMaxContact(env.DB,update);
    return j({ok:true,...saved});
  }

  if(url.pathname==="/api/health"){
    return j({
      ok:schema.ok,
      app:"NEUROGRAF WORK AI",
      assistant:"Мира",
      version:"1.2-memory",
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

  if(url.pathname==="/api/max/status" && request.method==="GET"){
    if(!env.MAX_BOT_TOKEN) return j({configured:false,webhook_secret:Boolean(env.MAX_WEBHOOK_SECRET)});
    try{
      const bot=await maxBotInfo(env);
      return j({configured:true,webhook_secret:Boolean(env.MAX_WEBHOOK_SECRET),bot:{user_id:bot.user_id,first_name:bot.first_name||bot.name||null,username:bot.username||null}});
    }catch(e){
      return j({configured:true,working:false,error:e.message},502);
    }
  }

  if(url.pathname==="/api/max/setup" && request.method==="POST"){
    if(!env.MAX_BOT_TOKEN) return j({error:"MAX_BOT_TOKEN not configured"},503);
    if(!env.MAX_WEBHOOK_SECRET) return j({error:"MAX_WEBHOOK_SECRET not configured"},503);
    const webhookUrl=url.origin+"/api/max/webhook";
    try{
      const result=await maxApi(env,"/subscriptions",{method:"POST",body:JSON.stringify({url:webhookUrl,update_types:["bot_started","message_created"],secret:env.MAX_WEBHOOK_SECRET})});
      return j({ok:true,webhook_url:webhookUrl,result});
    }catch(e){
      return j({error:"MAX setup failed",detail:e.message},502);
    }
  }

  const maxLinkMatch=url.pathname.match(/^\/api\/clients\/(\d+)\/max-link$/);
  if(maxLinkMatch && request.method==="GET"){
    const clientId=Number(maxLinkMatch[1]);
    const client=await env.DB.prepare("SELECT id,name FROM clients WHERE id=?").bind(clientId).first();
    if(!client) return j({error:"Клиент не найден"},404);
    if(!env.MAX_BOT_TOKEN) return j({error:"MAX_BOT_TOKEN not configured"},503);
    try{
      const bot=await maxBotInfo(env);
      if(!bot.username) return j({error:"У бота MAX нет username"},409);
      return j({ok:true,client_id:clientId,client_name:client.name,link:"https://max.ru/"+encodeURIComponent(bot.username)+"?start=client_"+clientId});
    }catch(e){
      return j({error:"MAX bot info failed",detail:e.message},502);
    }
  }

  const sendActionMatch=url.pathname.match(/^\/api\/actions\/(\d+)\/send$/);
  if(sendActionMatch && request.method==="POST"){
    const id=Number(sendActionMatch[1]);
    const action=await env.DB.prepare("SELECT id,action_type,target,payload,status FROM actions WHERE id=?").bind(id).first();
    if(!action) return j({error:"Черновик не найден"},404);
    if(action.status!=="approved") return j({error:"Сначала подтвердите черновик"},409);
    let payload={};
    try{payload=JSON.parse(action.payload||"{}");}catch{}
    if(payload.channel!=="max") return j({error:"Этот черновик не предназначен для MAX"},409);
    if(!env.MAX_BOT_TOKEN) return j({error:"MAX_BOT_TOKEN not configured"},503);
    const recipient=String(payload.recipient_name||action.target||"").trim();
    const client=await env.DB.prepare("SELECT id,name FROM clients WHERE lower(name)=lower(?) ORDER BY id DESC LIMIT 1").bind(recipient).first();
    if(!client) return j({error:"Клиент не найден в базе: "+recipient},404);
    const contact=await env.DB.prepare("SELECT user_id,username FROM max_contacts WHERE client_id=? ORDER BY updated_at DESC LIMIT 1").bind(client.id).first();
    if(!contact?.user_id) return j({error:"Клиент ещё не привязан к MAX. Сначала отправьте ему ссылку привязки."},409);
    const text=String(payload.text||"").trim().slice(0,4000);
    if(!text) return j({error:"Пустой текст сообщения"},400);
    try{
      const sent=await maxApi(env,"/messages?user_id="+encodeURIComponent(contact.user_id),{method:"POST",body:JSON.stringify({text,notify:true})});
      await env.DB.prepare("UPDATE actions SET status='sent',executed_at=CURRENT_TIMESTAMP,error=NULL WHERE id=?").bind(id).run();
      return j({ok:true,sent:true,status:"sent",recipient:client.name,max_user_id:contact.user_id,message:sent.message||sent});
    }catch(e){
      await env.DB.prepare("UPDATE actions SET error=? WHERE id=?").bind(String(e.message||"MAX send failed").slice(0,1000),id).run();
      return j({error:"MAX send failed",detail:e.message},502);
    }
  }

  if(url.pathname==="/api/memories" && request.method==="POST"){
    const b=await bodyJson(request);
    const content=String(b.content||"").trim().slice(0,4000);
    if(!content) return j({error:"content required"},400);
    const category=["preference","rule","profile","workflow","other"].includes(b.category)?b.category:"preference";
    const importance=Math.max(1,Math.min(5,Number(b.importance)||3));
    const r=await env.DB.prepare("INSERT INTO memories(category,content,importance) VALUES(?,?,?)").bind(category,content,importance).run();
    return j({ok:true,id:r.meta?.last_row_id||null});
  }

  const memoryMatch=url.pathname.match(/^\/api\/memories\/(\d+)$/);
  if(memoryMatch && request.method==="DELETE"){
    const id=Number(memoryMatch[1]);
    await env.DB.prepare("UPDATE memories SET active=0,updated_at=CURRENT_TIMESTAMP WHERE id=?").bind(id).run();
    return j({ok:true,id});
  }

  if(url.pathname==="/api/knowledge" && request.method==="POST"){
    const b=await bodyJson(request);
    const title=String(b.title||"").trim().slice(0,300);
    const content=String(b.content||"").trim().slice(0,12000);
    const source=String(b.source||"manual").trim().slice(0,300)||"manual";
    if(!title||!content) return j({error:"title and content required"},400);
    const r=await env.DB.prepare("INSERT INTO knowledge(title,content,source) VALUES(?,?,?)").bind(title,content,source).run();
    return j({ok:true,id:r.meta?.last_row_id||null});
  }

  const knowledgeMatch=url.pathname.match(/^\/api\/knowledge\/(\d+)$/);
  if(knowledgeMatch && request.method==="DELETE"){
    const id=Number(knowledgeMatch[1]);
    await env.DB.prepare("UPDATE knowledge SET active=0,updated_at=CURRENT_TIMESTAMP WHERE id=?").bind(id).run();
    return j({ok:true,id});
  }

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
  if(taskMatch && request.method==="DELETE"){
    const id=Number(taskMatch[1]);
    const existing=await env.DB.prepare("SELECT id,title FROM tasks WHERE id=?").bind(id).first();
    if(!existing) return j({error:"Задача не найдена"},404);
    await env.DB.prepare("DELETE FROM tasks WHERE id=?").bind(id).run();
    return j({ok:true,deleted:{id:existing.id,title:existing.title}});
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


  const actionMatch=url.pathname.match(/^\/api\/actions\/(\d+)$/);
  if(actionMatch && request.method==="PATCH"){
    const b=await bodyJson(request);
    const id=Number(actionMatch[1]);
    const existing=await env.DB.prepare("SELECT id,status,action_type,target,payload FROM actions WHERE id=?").bind(id).first();
    if(!existing) return j({error:"Черновик не найден"},404);

    if(b.status==="approved"){
      if(existing.status==="rejected") return j({error:"Отклонённый черновик нельзя подтвердить"},409);
      await env.DB.prepare("UPDATE actions SET status='approved',confirmed_at=CURRENT_TIMESTAMP WHERE id=?").bind(id).run();
      return j({
        ok:true,
        status:"approved",
        sent:false,
        message:"Черновик подтверждён к отправке, но реальный канал отправки ещё не подключён."
      });
    }

    if(b.status==="rejected"){
      await env.DB.prepare("UPDATE actions SET status='rejected' WHERE id=?").bind(id).run();
      return j({ok:true,status:"rejected",sent:false});
    }

    return j({error:"Допустимые статусы: approved или rejected"},400);
  }

  if(url.pathname==="/api/chat" && request.method==="POST"){
    if(!env.OPENAI_API_KEY) return j({error:"OPENAI_API_KEY not configured"},503);
    const b=await bodyJson(request);
    const message=String(b.message||"").trim().slice(0,12000);
    if(!message) return j({error:"message required"},400);

    await env.DB.prepare("INSERT INTO conversations(role,content) VALUES('user',?)").bind(message).run();
    const ctx=await appContext(env.DB);
    const localTime=String(b.client_local_time||"").slice(0,160);

    try{
      let activeModel=env.OPENAI_CHAT_MODEL||"gpt-5.6-luna";
      let response=await openAI(env,{
        model:activeModel,
        instructions:SYSTEM,
        tools:MIRA_TOOLS,
        input:"Текущее локальное время пользователя: "+(localTime||"не передано")+
          "\n\nКонтекст приложения:\n"+JSON.stringify(ctx)+
          "\n\nЗапрос пользователя:\n"+message
      });

      activeModel=response._mira_model||response.model||activeModel;
      const toolResults=[];
      const executedCalls=new Map();
      for(let round=0;round<3;round++){
        const calls=(Array.isArray(response.output)?response.output:[]).filter(x=>x?.type==="function_call");
        if(!calls.length) break;

        const outputs=[];
        for(const call of calls){
          let args={};
          try{ args=JSON.parse(call.arguments||"{}"); }catch{}
          const dedupeKey=call.name+"|"+JSON.stringify(args);
          let result;
          if(executedCalls.has(dedupeKey)){
            result=executedCalls.get(dedupeKey);
          }else{
            result=await executeMiraTool(call.name,args,env);
            executedCalls.set(dedupeKey,result);
          }
          toolResults.push({name:call.name,result});
          outputs.push({
            type:"function_call_output",
            call_id:call.call_id,
            output:JSON.stringify(result)
          });
        }

        response=await openAI(env,{
          model:activeModel,
          instructions:SYSTEM,
          tools:MIRA_TOOLS,
          previous_response_id:response.id,
          input:outputs
        });
        activeModel=response._mira_model||response.model||activeModel;
      }

      const answer=cleanAssistantText(extractText(response)||"Готово.");
      await env.DB.prepare("INSERT INTO conversations(role,content) VALUES('assistant',?)").bind(answer).run();
      return j({answer,refresh:toolResults.length>0,tool_results:toolResults});
    }catch(e){
      return j({error:"OpenAI error",detail:e?.detail||e?.message||"unknown"},502);
    }
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