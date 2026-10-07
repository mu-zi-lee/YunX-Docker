"use strict";
const $ = id => document.getElementById(id);
const names = { QUARK:"夸克网盘", UC:"UC 网盘", XUNLEI:"迅雷网盘", BAIDU:"百度网盘", C139:"139 网盘", PAN123:"123 云盘", PAN115:"115 网盘", GUANGYA:"光鸭云盘", GITHUB:"GitHub" };
const statuses = ["等待中","下载中","已暂停","已完成","失败"];
let activeView = "cloud", accountStates = [], taskStates = [], taskFilter = "all", loggedIn = false, pendingRemoval = "", libraryTab = "bookmarks";
let libraryData = {bookmarks:[],history:[]};
let settingsGeneration = 0;
const profilePending = new Set();
const browsers = {};
function icons() { lucide.createIcons(); }
function el(tag, cls, text) {
  const node = document.createElement(tag);
  if (cls) node.className = cls;
  if (text !== undefined) node.textContent = text;
  return node;
}
function icon(name) { const node = el("i"); node.dataset.lucide = name; return node; }
function command(label, name, action, cls = "icon-button") {
  const node = el("button", cls); node.type = "button"; node.title = label; node.setAttribute("aria-label", label);
  if (name) node.append(icon(name));
  if (cls !== "icon-button") node.append(el("span", "", label));
  node.addEventListener("click", () => busy(node, action));
  return node;
}
function notice(text, error = false, target = "notice") {
  const node = $(target); node.textContent = text; node.hidden = false;
  node.classList.toggle("error", error); node.classList.toggle("success", !error && !!text);
}
async function busy(control, action, target = "notice") {
  if (control.disabled) return;
  control.disabled = true;
  try { await action(); } catch (error) { notice(error.message, true, target); }
  finally { control.disabled = false; }
}
async function api(path, body) {
  const response = await fetch("/api/" + path, body === undefined ? {} : {
    method:"POST", headers:{"Content-Type":"application/json","X-YunX-Request":"1"}, body:JSON.stringify(body)
  });
  const data = await response.json();
  if (!response.ok) {
    if (response.status === 401 && path !== "auth/login") showLogin("登录已过期，请重新登录");
    throw new Error(data.error || "请求失败");
  }
  return data;
}
function size(bytes) {
  if (!Number.isFinite(bytes) || bytes < 0) return "—";
  const units = ["B","KiB","MiB","GiB","TiB"], power = Math.min(4, Math.floor(Math.log(Math.max(1, bytes))/Math.log(1024)));
  return (bytes / 1024 ** power).toFixed(power ? 2 : 0) + " " + units[power];
}
function modified(value) {
  if (!value) return "—";
  if (typeof value === "string" && /^\d{4}-\d{2}-\d{2}$/.test(value)) return value.replaceAll("-", "/");
  const number = Number(value);
  const date = Number.isFinite(number) && number > 0 ? new Date(number < 100000000000 ? number*1000 : number) : new Date(value);
  return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleDateString("zh-CN");
}
function theme(value) {
  try { localStorage.setItem("yunx-theme", value); } catch {}
  const actual = value === "system" ? (matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light") : value;
  document.documentElement.dataset.theme = actual;
  document.querySelectorAll("[data-theme]").forEach(button => {
    if (button.tagName !== "BUTTON") return;
    button.classList.toggle("active", button.dataset.theme === value);
    button.setAttribute("aria-pressed", String(button.dataset.theme === value));
  });
}
let preferredTheme = "system";
try { preferredTheme = localStorage.getItem("yunx-theme") || "system"; } catch {}
theme(preferredTheme);
matchMedia("(prefers-color-scheme: dark)").addEventListener("change", () => { if (preferredTheme === "system") theme("system"); });
document.querySelectorAll(".theme-toggle").forEach(button => button.addEventListener("click", () => {
  preferredTheme = document.documentElement.dataset.theme === "dark" ? "light" : "dark"; theme(preferredTheme);
}));
$("theme-options").querySelectorAll("button").forEach(button => button.addEventListener("click", () => { preferredTheme = button.dataset.theme; theme(preferredTheme); }));
function showLogin(message = "") {
  loggedIn = false;
  $("app").hidden = true; $("login-view").hidden = false;
  for (const dialog of document.querySelectorAll("dialog[open]")) dialog.close();
  notice(message, false, "login-status");
  $("login-password").value = "";
  icons();
}
async function showApp(session) {
  loggedIn = true; $("login-view").hidden = true; $("app").hidden = false;
  $("current-user").textContent = session.username;
  $("password-form").querySelectorAll("input,button").forEach(node => node.disabled = session.passwordManaged);
  notice(session.passwordManaged ? "密码由部署配置 YUNX_PASSWORD 管理。" : "", false, "password-status");
  await loadAccounts();
  await view(activeView);
}
async function view(name) {
  activeView = name;
  for (const key of ["cloud","resolve","tasks","accounts","settings"]) $(key+"-view").hidden = name !== key;
  document.querySelectorAll("nav button").forEach(button => {
    button.classList.toggle("active", button.dataset.view === name);
    if (button.dataset.view === name) button.setAttribute("aria-current","page"); else button.removeAttribute("aria-current");
  });
  $("page-label").textContent = "WORKSPACE / " + name.toUpperCase();
  $("notice").hidden = true;
  if (name === "tasks") await refreshTasks();
  if (name === "resolve") await loadLibrary();
  if (name === "accounts") { await loadAccounts(); refreshProfiles(); }
  if (name === "cloud" && !browsers.cloud && $("cloud-platform").value) await loadCloud();
  if (name === "settings") {
    const generation = ++settingsGeneration;
    $("settings-form").querySelectorAll("input,button").forEach(node=>node.disabled=true);
    const data = await api("settings");
    if (generation !== settingsGeneration) return;
    $("setting-threads").value = data.threads; $("setting-concurrency").value = data.concurrency;
    $("setting-speed").value = +(data.speedLimit/1048576).toFixed(2);
    $("settings-form").querySelectorAll("input,button").forEach(node=>node.disabled=false);
  }
}
document.querySelectorAll("nav button").forEach(button => button.addEventListener("click", () => busy(button, () => view(button.dataset.view))));
$("login-form").addEventListener("submit", event => {
  event.preventDefault();
  busy(event.submitter, async () => {
    await api("auth/login", { username:$("login-username").value, password:$("login-password").value });
    $("login-password").value = "";
    await showApp(await api("auth/session"));
  }, "login-status");
});
async function logout() { await api("auth/logout", {}); showLogin(); }
$("logout").addEventListener("click", () => busy($("logout"), logout));
$("mobile-logout").addEventListener("click", () => busy($("mobile-logout"), logout));
document.querySelectorAll(".reveal").forEach(button => button.addEventListener("click", () => {
  const input = $(button.dataset.input); input.type = input.type === "password" ? "text" : "password";
  button.setAttribute("aria-pressed", String(input.type === "text"));
}));
function credentialFields(platform) {
  if (platform === "XUNLEI") return [["accessToken","Access Token"],["refreshToken","Refresh Token"],["deviceId","Device ID"],["captchaToken","Captcha Token"]];
  if (platform === "GUANGYA") return [["accessToken","Access Token"],["deviceId","Device ID"],["deviceSign","Device Sign"]];
  if (["PAN123","GITHUB"].includes(platform)) return [["accessToken",platform === "GITHUB" ? "Personal Access Token" : "Access Token"]];
  return [["cookie","Cookie"]];
}
function accountFields() {
  const platform = $("account-platform").value;
  $("credential-fields").replaceChildren(); $("account-status").textContent = "";
  for (const [key,label] of credentialFields(platform)) {
    const input = el("input"); input.id = "credential-"+key; input.name = key; input.type = "password";
    input.autocomplete = "off"; input.maxLength = 65536;
    input.required = ["cookie","accessToken"].includes(key);
    const node = el("label","",label); node.htmlFor = input.id;
    $("credential-fields").append(node,input);
  }
  if (platform === "XUNLEI") {
    const label = el("label","","登录通道"); label.htmlFor = "credential-authType";
    const select = el("select"); select.id = "credential-authType"; select.name = "authType";
    for (const [value,name] of [["","App"],["webToken","网页"]]) { const option = el("option","",name); option.value = value; select.append(option); }
    select.value = accountStates.find(a=>a.platform === platform)?.authType || "";
    $("credential-fields").append(label,select);
  }
}
function openAccount(platform = "") {
  $("account-form").reset();
  const available = Object.keys(names).filter(p => platform ? p === platform : !accountStates.find(a => a.platform === p)?.configured);
  if (!available.length) { notice("所有支持的平台均已配置"); return; }
  $("account-platform").replaceChildren(...available.map(p => { const option = el("option","",names[p]); option.value = p; return option; }));
  $("account-title").textContent = platform ? "更新 "+names[platform]+" 凭证" : "添加网盘账号";
  accountFields(); $("account-dialog").showModal(); icons();
}
for (const id of ["account-add","cloud-add","empty-add"]) $(id).addEventListener("click", () => openAccount());
$("account-platform").addEventListener("change", accountFields);
$("close-account").addEventListener("click", () => $("account-dialog").close());
$("account-form").addEventListener("submit", event => {
  event.preventDefault();
  busy(event.submitter, async () => {
    const platform = $("account-platform").value;
    const credentials = Object.fromEntries([...$("credential-fields").querySelectorAll("input,select")].map(input => [input.name,input.value.trim()]));
    await api("accounts", { platform,credentials });
    $("account-form").reset(); $("account-dialog").close(); delete browsers.cloud;
    await loadAccounts(); notice("账号已保存"); refreshProfiles();
  }, "account-status");
});
async function loadAccounts() {
  const data = await api("accounts");
  accountStates = data.filter(a => names[a.platform]);
  renderAccounts();
  const previous = $("cloud-platform").value;
  const accounts = accountStates.filter(a => a.configured && a.cloudSupported);
  $("cloud-platform").replaceChildren(...accounts.map(a => {
    const option = el("option","",names[a.platform]); option.value = a.platform; return option;
  }));
  if (accounts.some(a => a.platform === previous)) $("cloud-platform").value = previous;
  $("cloud-empty").hidden = accounts.length > 0;
  $("cloud-platform").disabled = !accounts.length; $("cloud-refresh").disabled = !accounts.length;
  if (!accounts.length) { $("cloud-browser").replaceChildren(); $("cloud-capacity").textContent = ""; delete browsers.cloud; }
}
function renderAccounts() {
  const configured = accountStates.filter(a => a.configured), available = accountStates.filter(a => !a.configured);
  $("account-count").textContent = configured.length + " ACCOUNTS";
  $("available-count").textContent = available.length;
  $("account-list").replaceChildren();
  if (!configured.length) $("account-list").append(el("div","empty","尚未添加账号"));
  for (const account of configured) {
    const p = account.platform, card = el("article","account-card"); card.dataset.platform = p;
    const head = el("div","account-head"), info = el("div");
    info.append(el("h2","",names[p]),el("span","metadata", account.profile?.nickname || "已配置凭证"));
    head.append(icon("cloud"),info); card.append(head);
    const q = account.profile?.quota;
    if (q && q.total > 0) {
      const value = el("strong","capacity-value",size(q.used)); value.append(el("small","","已用")); card.append(value);
      const meter = el("div","quota-bar"), ratio = Math.min(1,Math.max(0,q.used/q.total));
      meter.setAttribute("role","meter"); meter.setAttribute("aria-label",names[p]+" 存储使用率");
      meter.setAttribute("aria-valuenow",String(Math.round(ratio*100))); meter.setAttribute("aria-valuemin","0"); meter.setAttribute("aria-valuemax","100");
      for (let i=0;i<24;i++) meter.append(el("i", i<Math.ceil(ratio*24) ? "filled"+(ratio>.9 ? " warning":"") : ""));
      const meta = el("div","capacity-meta"); meta.append(el("span","","总容量 "+size(q.total)),el("span","",Math.round(ratio*100)+"%"));
      card.append(meter,meta);
    } else {
      card.append(el("strong","capacity-value","—"),el("div","capacity-meta",profilePending.has(p) ? "读取容量中…" : p === "GITHUB" ? "代码托管账号" : account.profile ? "容量暂不可用" : "容量尚未读取"));
    }
    const actions = el("div","toolbar");
    if (account.cloudSupported) actions.append(command("浏览文件","folder-open",async () => { await view("cloud"); $("cloud-platform").value = p; await loadCloud(); },"secondary"));
    actions.append(command("刷新容量","refresh-cw",() => refreshProfile(p)),command("更新凭证","key-round",() => openAccount(p)),command("移除账号","unlink",() => {
      pendingRemoval = p; $("confirm-text").textContent = "移除 "+names[p]+" 的凭证？已有下载文件会保留。"; $("confirm-dialog").showModal();
    }));
    card.append(actions); $("account-list").append(card);
  }
  $("available-list").replaceChildren(...available.map(account => command(names[account.platform],"plus",() => openAccount(account.platform),"available-account")));
  icons();
}
async function refreshProfile(platform) {
  if (profilePending.has(platform)) return;
  profilePending.add(platform); renderAccounts();
  try {
    const profile = await api("account-info",{platform});
    const account = accountStates.find(a => a.platform === platform);
    if (account?.configured) account.profile = profile;
  } catch (error) {
    const account = accountStates.find(a => a.platform === platform);
    if (account) account.profile = {error:error.message};
    if (loggedIn && activeView === "accounts") notice(names[platform]+"："+error.message,true);
  } finally { profilePending.delete(platform); renderAccounts(); }
  updateCloudCapacity();
}
async function refreshProfiles() {
  for (const account of accountStates.filter(a => a.configured && !a.profile && a.platform !== "GITHUB")) {
    if (!loggedIn) break;
    await refreshProfile(account.platform);
  }
}
$("confirm-cancel").addEventListener("click", () => $("confirm-dialog").close());
$("confirm-remove").addEventListener("click", () => busy($("confirm-remove"),async () => {
  await api("accounts",{platform:pendingRemoval,credentials:{}});
  $("confirm-dialog").close(); delete browsers.cloud; await loadAccounts(); notice("账号已移除");
}));
function updateCloudCapacity() {
  const q = accountStates.find(a => a.platform === $("cloud-platform").value)?.profile?.quota;
  $("cloud-capacity").textContent = q?.total > 0 ? size(q.used)+" / "+size(q.total) : "";
}
let cloudGeneration = 0;
async function loadCloud() {
  const platform = $("cloud-platform").value; if (!platform) return;
  const generation = ++cloudGeneration;
  delete browsers.cloud; $("cloud-browser").replaceChildren(el("div","empty","[读取目录中…]"));
  try {
    const result = await api("cloud/files",{platform});
    if (generation !== cloudGeneration || !loggedIn) return;
    makeBrowser("cloud",result);
    updateCloudCapacity();
  } catch (error) {
    if (generation === cloudGeneration) $("cloud-browser").replaceChildren(el("div","empty",error.message));
    throw error;
  }
}
$("cloud-platform").addEventListener("change", () => busy($("cloud-refresh"),loadCloud));
$("cloud-refresh").addEventListener("click", () => busy($("cloud-refresh"),loadCloud));
async function resolve(link,password = "") {
  const result = await api("resolve",{link,password});
  if (result.directUrl) { await api("direct",{url:result.directUrl,filename:result.filename}); notice("已加入下载"); await view("tasks"); return; }
  makeBrowser("share",result);
  browsers.share.link = link; browsers.share.password = password;
  buildBrowser(browsers.share);
  await loadLibrary();
  notice("解析完成");
}
async function loadLibrary() { libraryData = await api("library"); renderLibrary(); }
function renderLibrary() {
  $("library-list").replaceChildren();
  for (const item of libraryData[libraryTab]) {
    const row = el("div","saved-link");
    row.append(command(item.title || item.link,"link",async () => {
      $("link").value = item.link; $("password").value = item.password;
      await resolve(item.link,item.password);
    },"saved-link-open"));
    row.append(command("移除链接","x",async () => { libraryData = await api("library",{kind:libraryTab,action:"remove",id:item.id}); renderLibrary(); }));
    $("library-list").append(row);
  }
  if (!libraryData[libraryTab].length) $("library-list").append(el("div","empty",libraryTab === "bookmarks" ? "暂无收藏链接" : "暂无解析记录"));
  icons();
}
$("library-tabs").querySelectorAll("button").forEach(button => button.addEventListener("click",() => {
  libraryTab = button.dataset.library; $("library-tabs").querySelectorAll("button").forEach(b=>b.classList.toggle("active",b===button)); renderLibrary();
}));
$("resolve-form").addEventListener("submit",event => {
  event.preventDefault(); busy(event.submitter,() => resolve($("link").value,$("password").value));
});
$("direct-form").addEventListener("submit",event => {
  event.preventDefault(); busy(event.submitter,async () => {
    await api("direct",{url:$("direct-url").value,filename:$("direct-name").value});
    $("direct-form").reset(); await view("tasks"); notice("已加入下载");
  });
});
function makeBrowser(kind,result) {
  const state = { kind, result, files:result.files || result.assets || result.repositories || [],
    stack:[{id:result.directory || "",name:result.title || "根目录"}], selected:new Set(), query:"", sort:"name", layout:"list",page:1 };
  browsers[kind] = state; buildBrowser(state);
}
async function navigate(state,index,file) {
  const generation = state.generation = (state.generation || 0)+1;
  const directory = file ? file.directoryId ?? file.fid : state.stack[index].id;
  const result = await api(state.kind === "cloud" ? "cloud/files" : "files",{
    sessionId:state.result.sessionId,directory,page:1
  });
  if (browsers[state.kind] !== state || generation !== state.generation) return;
  state.stack = file ? [...state.stack,{id:directory,name:file.name}] : state.stack.slice(0,index+1);
  state.result = {...result,sessionId:state.result.sessionId}; state.files = result.files || [];
  state.selected.clear(); state.page = 1; state.query = "";
  buildBrowser(state);
}
async function downloadFile(state,file) {
  if (file.url) return api("direct",{url:file.url,filename:file.name});
  return api(state.kind === "cloud" ? "cloud/download" : "download",{sessionId:state.result.sessionId,fid:file.fid});
}
function buildBrowser(state) {
  const root = $(state.kind+"-browser"); root.replaceChildren(); root.className = "file-browser";
  const title = el("div","browser-title"); title.append(icon("folder-open"),el("h2","",state.result.title),el("span","metadata",names[state.result.platform] || "GitHub")); root.append(title);
  title.hidden = state.kind === "cloud";
  if (state.kind === "share" && state.link) title.append(command("收藏链接","bookmark",async () => {
    libraryData = await api("library",{kind:"bookmarks",link:state.link,password:state.password,title:state.result.title});
    renderLibrary(); notice("链接已收藏");
  }));
  const crumbs = el("div","breadcrumbs");
  state.stack.forEach((dir,index) => {
    crumbs.append(command(dir.name,null,() => navigate(state,index), "breadcrumb"));
    if (index < state.stack.length-1) crumbs.append(icon("chevron-right"));
  }); root.append(crumbs);
  const toolbar = el("div","list-toolbar"), controls = el("div","toolbar file-controls");
  const search = el("input","file-search"); search.type = "search"; search.placeholder = "搜索当前目录"; search.setAttribute("aria-label","搜索当前目录"); search.value = state.query;
  search.addEventListener("input",() => { state.query = search.value; renderFiles(state); });
  const sort = el("select","file-sort"); sort.setAttribute("aria-label","文件排序");
  for (const [value,name] of [["name","名称"],["size","大小"],["time","修改时间"]]) { const option = el("option","",name); option.value = value; sort.append(option); }
  sort.value = state.sort; sort.addEventListener("change",() => { state.sort = sort.value; renderFiles(state); });
  const layout = el("div","segmented");
  for (const [name,label,glyph] of [["list","列表视图","list"],["grid","网格视图","layout-grid"]]) {
    const button = command(label,glyph,() => {
      state.layout = name; layout.querySelectorAll("button").forEach(b => { b.classList.toggle("active",b.dataset.layout === name); b.setAttribute("aria-pressed",String(b.dataset.layout === name)); });
      renderFiles(state);
    });
    button.dataset.layout = name; button.classList.toggle("active",state.layout === name); button.setAttribute("aria-pressed",String(state.layout === name)); layout.append(button);
  }
  controls.append(search,sort,layout); toolbar.append(controls); root.append(toolbar);
  const selection = el("div","selection-bar"), label = el("label","select-all"), checkbox = el("input");
  checkbox.type = "checkbox"; checkbox.setAttribute("aria-label","选择当前显示的文件");
  label.append(checkbox,el("span","","全选")); selection.append(label);
  const queue = command("下载所选文件","download",async () => {
    let success = 0; const errors = [];
    for (const file of state.files.filter(f => state.selected.has(f.fid || f.url))) {
      try { await downloadFile(state,file); success++; state.selected.delete(file.fid || file.url); }
      catch (error) { errors.push(file.name+"："+error.message); }
    }
    notice("已加入 "+success+" 个下载"+(errors.length ? "；"+errors.join("；") : ""),errors.length > 0);
    renderFiles(state);
  },"secondary");
  checkbox.addEventListener("change",() => {
    for (const file of visibleFiles(state).filter(f => !f.directory && !state.result.repositories)) {
      const key = file.fid || file.url; if (checkbox.checked) state.selected.add(key); else state.selected.delete(key);
    }
    renderFiles(state);
  });
  selection.append(queue); root.append(selection);
  state.checkAll = checkbox; state.queueButton = queue;
  state.rows = el("div"); root.append(state.rows);
  if (state.result.hasMore) root.append(command("加载更多","chevron-down",async () => {
    const generation = state.generation;
    const next = await api("cloud/files",{sessionId:state.result.sessionId,directory:state.result.directory,page:state.page+1,cursor:state.result.cursor});
    if (browsers[state.kind] !== state || generation !== state.generation) return;
    state.page++; state.result = next;
    const seen = new Set(state.files.map(f=>f.fid)); state.files.push(...next.files.filter(f=>!seen.has(f.fid)));
    buildBrowser(state);
  },"secondary load-more"));
  if (state.result.limited) root.append(el("p","metadata","当前接口仅返回首批文件。"));
  renderFiles(state);
}
function visibleFiles(state) {
  return state.files.filter(file => file.name.toLocaleLowerCase().includes(state.query.toLocaleLowerCase())).sort((a,b) =>
    Number(b.directory)-Number(a.directory) || (state.sort === "size" ? (b.size || 0)-(a.size || 0) : state.sort === "time" ? String(b.modified || "").localeCompare(String(a.modified || "")) : a.name.localeCompare(b.name,"zh-CN")));
}
function renderFiles(state) {
  const files = visibleFiles(state);
  state.rows.replaceChildren(); state.rows.className = state.layout === "grid" ? "file-grid" : "file-list";
  state.queueButton.disabled = !state.selected.size;
  state.queueButton.querySelector("span").textContent = state.selected.size ? "下载所选 · "+state.selected.size : "下载所选文件";
  const selectable = files.filter(f => !f.directory && !state.result.repositories);
  state.checkAll.checked = !!selectable.length && selectable.every(f => state.selected.has(f.fid || f.url));
  state.checkAll.indeterminate = selectable.some(f => state.selected.has(f.fid || f.url)) && !state.checkAll.checked;
  state.checkAll.disabled = !selectable.length;
  if (!files.length) state.rows.append(el("div","empty",state.query ? "没有匹配的文件" : "此目录没有文件"));
  else if (state.layout === "list") {
    const header = el("div","file-header");
    header.append(el("span"),el("span"),el("span","","名称"),el("span","file-size","大小"),el("span","file-time","修改时间"),el("span"));
    state.rows.append(header);
  }
  for (const file of files) {
    const row = el("div","file-row"), check = el("input"); check.type = "checkbox";
    check.disabled = !!file.directory || !!state.result.repositories; check.checked = state.selected.has(file.fid || file.url);
    check.setAttribute("aria-label","选择 "+file.name);
    check.addEventListener("change",() => {
      const key = file.fid || file.url; if (check.checked) state.selected.add(key); else state.selected.delete(key);
      state.queueButton.disabled = !state.selected.size; state.queueButton.querySelector("span").textContent = "下载所选 · "+state.selected.size;
      state.checkAll.checked = selectable.every(f => state.selected.has(f.fid || f.url)); state.checkAll.indeterminate = state.selected.size > 0 && !state.checkAll.checked;
    });
    const open = async () => {
      if (state.result.repositories) await resolve(file.url);
      else if (file.directory) await navigate(state,-1,file);
      else { await downloadFile(state,file); notice("已加入下载"); }
    };
    row.append(check,icon(file.directory ? "folder" : state.result.repositories ? "github" : "file"));
    row.append(command(file.name,null,open,"file-name"));
    row.append(el("span","file-size",file.directory ? "—" : size(file.size || 0)),el("span","file-time",modified(file.modified)));
    row.append(command(file.directory || state.result.repositories ? "打开" : "下载",file.directory || state.result.repositories ? "chevron-right" : "download",open));
    state.rows.append(row);
  }
  icons();
}
async function refreshTasks() {
  const tasks = await api("tasks"); taskStates = tasks;
  $("task-count").textContent = tasks.filter(t => t.status <= 1).length;
  $("total-speed").textContent = size(tasks.reduce((sum,t) => sum+(t.status===1 ? t.speed : 0),0))+"/s";
  $("active-count").textContent = tasks.filter(t => t.status <= 1).length; $("done-count").textContent = tasks.filter(t => t.status === 3).length;
  if (activeView !== "tasks" || $("tasks").querySelector("button:disabled")) return;
  const focused = $("tasks").contains(document.activeElement) ? {id:document.activeElement.closest(".task-row")?.dataset.id,title:document.activeElement.getAttribute("aria-label")} : null;
  const shown = tasks.filter(t => taskFilter === "all" || (taskFilter === "active" ? t.status <= 2 : taskFilter === "done" ? t.status === 3 : t.status === 4));
  $("tasks").replaceChildren();
  if (!shown.length) $("tasks").append(el("div","empty","暂无下载任务"));
  for (const task of shown) {
    const row = el("div","task-row"); row.dataset.id = String(task.id); row.append(icon(task.status === 3 ? "circle-check" : "file"));
    const detail = el("div","task-detail"), top = el("div","task-top");
    top.append(el("div","file-name",task.filename),el("span","task-status"+(task.status === 3 ? " complete" : task.status === 4 ? " failed" : ""),task.mergePercent >= 0 ? "合并中 "+task.mergePercent+"%" : statuses[task.status]));
    const progress = el("progress"); progress.max = Math.max(task.total,task.downloaded,1); progress.value = task.status === 3 ? progress.max : task.downloaded; progress.setAttribute("aria-label",task.filename+" 下载进度");
    detail.append(top,progress,el("div","metadata",size(task.downloaded)+" / "+(task.total ? size(task.total) : "未知大小")+(task.speed ? " · "+size(task.speed)+"/s" : "")));
    if (task.status === 3) detail.append(el("div","metadata",task.savePath));
    if (task.status === 4 && task.error) detail.append(el("div","error",task.error));
    const actions = el("div","task-actions");
    const action = async type => { await api("tasks",{id:task.id,action:type}); notice(type === "remove" ? "任务记录已移除" : "操作已提交"); };
    if (task.status <= 1) actions.append(command("暂停","pause",() => action("pause")));
    if ([2,4].includes(task.status)) actions.append(command("继续下载","play",() => action("resume")));
    actions.append(command("移除任务记录","x",() => action("remove")));
    row.append(detail,actions); $("tasks").append(row);
  }
  icons();
  if (focused) {
    const row = [...$("tasks").children].find(r => r.dataset.id === focused.id);
    (row && ([...row.querySelectorAll("button")].find(b=>b.getAttribute("aria-label") === focused.title) || row.querySelector("button")))?.focus();
  }
}
$("refresh").addEventListener("click",() => busy($("refresh"),refreshTasks));
$("task-filters").querySelectorAll("button").forEach(button => button.addEventListener("click",() => busy(button,async () => {
  taskFilter = button.dataset.filter; $("task-filters").querySelectorAll("button").forEach(b => b.classList.toggle("active",b === button)); await refreshTasks();
})));
async function taskBatch(action) {
  let count = 0; const failures = [];
  for (const task of taskStates.filter(t => action === "pause" ? t.status <= 1 : [2,4].includes(t.status))) {
    try { await api("tasks",{id:task.id,action}); count++; } catch (error) { failures.push(error.message); }
  }
  await refreshTasks(); notice("已提交 "+count+" 个任务"+(failures.length ? "；"+failures.join("；") : ""),!!failures.length);
}
$("pause-all").addEventListener("click",() => busy($("pause-all"),() => taskBatch("pause")));
$("resume-all").addEventListener("click",() => busy($("resume-all"),() => taskBatch("resume")));
$("settings-form").addEventListener("submit",event => {
  event.preventDefault(); busy(event.submitter,async () => {
    notice("",false,"settings-status");
    await api("settings",{threads:Number($("setting-threads").value),concurrency:Number($("setting-concurrency").value),speedLimit:Math.round(Number($("setting-speed").value)*1048576)});
    notice("下载设置已保存",false,"settings-status");
  },"settings-status");
});
$("password-form").addEventListener("submit",event => {
  event.preventDefault(); busy(event.submitter,async () => {
    if ($("new-password").value !== $("confirm-password").value) throw new Error("两次输入的新密码不一致");
    await api("auth/password",{current:$("current-password").value,password:$("new-password").value});
    $("password-form").reset(); showLogin("密码已修改，请使用新密码登录");
  },"password-status");
});
setInterval(() => { if (loggedIn) refreshTasks().catch(error => { if (activeView === "tasks") notice(error.message,true); }); },2500);
icons();
api("auth/session").then(session => session.authenticated ? showApp(session) : showLogin()).catch(error => showLogin(error.message));
