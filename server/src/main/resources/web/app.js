"use strict";
const $ = id => document.getElementById(id);
const names = { QUARK: "夸克网盘", UC: "UC 网盘", XUNLEI: "迅雷网盘", BAIDU: "百度网盘", C139: "139 网盘", PAN123: "123 云盘", PAN115: "115 网盘", GUANGYA: "光鸭云盘", ILANZOU: "蓝奏优享", LANZOU: "蓝奏云", GITHUB: "GitHub" };
let activeView = "resolve", activeAccount = "QUARK", accountStates = [];
let sessionId = "", directoryStack = [], currentFiles = [], noticeTimer;

function icons() { lucide.createIcons(); }
function element(tag, className, text) {
  const el = document.createElement(tag);
  if (className) el.className = className;
  if (text !== undefined) el.textContent = text;
  return el;
}
function icon(name) { const el = element("i"); el.dataset.lucide = name; return el; }
function button(name, title, action) {
  const el = element("button", "icon-button");
  el.type = "button"; el.title = title; el.setAttribute("aria-label", title);
  el.append(icon(name));
  el.addEventListener("click", () => busy(el, action));
  return el;
}
function notify(message, error = false) {
  clearTimeout(noticeTimer);
  $("notice").textContent = message;
  $("notice").className = error ? "error-notice" : "";
  $("notice").hidden = false;
  noticeTimer = setTimeout(() => { $("notice").hidden = true; }, error ? 12000 : 4000);
}
async function api(path, body) {
  const options = body === undefined ? {} : {
    method: "POST", headers: { "Content-Type": "application/json", "X-YunX-Request": "1" }, body: JSON.stringify(body)
  };
  const response = await fetch("/api/" + path, options);
  const value = await response.json();
  if (!response.ok) throw new Error(value.error || `请求失败 (${response.status})`);
  return value;
}
async function busy(control, action) {
  if (control.disabled) return;
  control.disabled = true;
  try { await action(); } catch (error) { notify(error.message, true); }
  finally { control.disabled = false; }
}
function view(name) {
  activeView = name;
  for (const section of ["resolve", "tasks", "accounts"]) $(section + "-view").hidden = section !== name;
  document.querySelectorAll("nav button").forEach(b => {
    b.classList.toggle("active", b.dataset.view === name);
    if (b.dataset.view === name) b.setAttribute("aria-current", "page"); else b.removeAttribute("aria-current");
  });
  if (name === "tasks") refreshTasks().catch(e => notify(e.message, true));
  if (name === "accounts") loadAccounts().catch(e => notify(e.message, true));
}
function size(value) {
  if (!value) return "0 B";
  const units = ["B", "KB", "MB", "GB", "TB"];
  const power = Math.min(4, Math.floor(Math.log(Math.max(1, value)) / Math.log(1024)));
  return (value / 1024 ** power).toFixed(power > 1 ? 2 : 0) + " " + units[power];
}
async function resolve(link, password = "") {
  const result = await api("resolve", { link, password });
  sessionId = result.sessionId || "";
  directoryStack = [{ id: result.directory || "", name: result.title || "根目录" }];
  if (result.directUrl) {
    await api("direct", { url: result.directUrl, filename: result.filename });
    notify("已加入下载"); view("tasks"); return;
  }
  renderFiles(result);
}
function renderFiles(result) {
  $("browse").hidden = false;
  $("platform").textContent = names[result.platform] || "GitHub";
  $("share-title").textContent = result.title;
  $("breadcrumbs").textContent = directoryStack.map(d => d.name).join(" / ");
  $("back").disabled = directoryStack.length <= 1;
  const container = $("files");
  container.replaceChildren();
  currentFiles = result.files || [];
  const entries = result.files || result.assets || result.repositories || [];
  if (!entries.length) container.append(element("div", "empty", "此目录没有文件"));
  for (const file of entries) {
    const row = element("div", "file-row");
    row.append(icon(file.directory ? "folder" : result.repositories ? "github" : "file"));
    const details = element("div", "file-detail");
    details.append(element("div", "file-name", file.name));
    if (!file.directory && file.size !== undefined) details.append(element("div", "metadata", size(file.size)));
    row.append(details);
    row.append(button(file.directory || result.repositories ? "chevron-right" : "download",
      file.directory || result.repositories ? "打开" : "下载", async () => {
        if (result.repositories) {
          await resolve(file.url);
        } else if (file.directory) {
          const next = await api("files", { sessionId, directory: file.fid });
          directoryStack.push({ id: file.fid, name: file.name }); renderFiles(next);
        } else {
          if (file.url) await api("direct", { url: file.url, filename: file.name });
          else await api("download", { sessionId, fid: file.fid });
          notify("已加入下载"); refreshTasks().catch(() => {});
        }
      }));
    container.append(row);
  }
  icons();
}
const statuses = ["等待中", "下载中", "已暂停", "已完成", "失败"];
async function refreshTasks() {
  const tasks = await api("tasks");
  $("task-count").textContent = tasks.filter(t => t.status <= 1).length;
  if (activeView !== "tasks") return;
  // Finish a pending action before replacing its control, then restore keyboard focus.
  if ($("tasks").querySelector("button:disabled")) return;
  const focused = $("tasks").contains(document.activeElement) ? {
    id: document.activeElement.closest(".task-row")?.dataset.id,
    title: document.activeElement.getAttribute("aria-label")
  } : null;
  const container = $("tasks"); container.replaceChildren();
  if (!tasks.length) container.append(element("div", "empty", "暂无下载任务"));
  for (const task of tasks) {
    const row = element("div", "task-row"); row.dataset.id = String(task.id); row.append(icon(task.status === 3 ? "circle-check" : "file"));
    const detail = element("div", "task-detail"); detail.append(element("div", "file-name", task.filename));
    detail.append(element("span", "task-status " + (task.status === 3 ? "complete" : task.status === 4 ? "failed" : ""), task.mergePercent >= 0 ? `合并中 ${task.mergePercent}%` : statuses[task.status]));
    const progress = element("progress");
    progress.max = Math.max(task.total, task.downloaded, 1); progress.value = task.status === 3 ? progress.max : task.downloaded;
    progress.setAttribute("aria-label", task.filename + " 下载进度"); detail.append(progress);
    detail.append(element("div", "metadata", `${size(task.downloaded)} / ${task.total ? size(task.total) : "未知大小"}${task.speed ? " · " + size(task.speed) + "/s" : ""}`));
    if (task.status === 3) detail.append(element("div", "metadata", task.savePath));
    if (task.error && task.status === 4) detail.append(element("div", "error", task.error));
    row.append(detail);
    const actions = element("div", "task-actions");
    const action = async type => { await api("tasks", { id: task.id, action: type }); notify(type === "remove" ? "任务记录已移除" : "操作已提交"); };
    if (task.status <= 1) actions.append(button("pause", "暂停", () => action("pause")));
    if (task.status === 2 || task.status === 4) actions.append(button("play", "继续下载", () => action("resume")));
    actions.append(button("x", "移除任务记录", () => action("remove"))); row.append(actions); container.append(row);
  }
  icons();
  if (focused) {
    const row = [...container.querySelectorAll(".task-row")].find(el => el.dataset.id === focused.id);
    const control = row && ([...row.querySelectorAll("button")].find(el => el.getAttribute("aria-label") === focused.title) || row.querySelector("button"));
    control?.focus({ preventScroll: true });
  }
}
function credentialFields(platform) {
  if (platform === "XUNLEI") return [["accessToken", "Access Token"], ["refreshToken", "Refresh Token"], ["deviceId", "Device ID"], ["captchaToken", "Captcha Token"]];
  if (["PAN123", "GUANGYA", "GITHUB"].includes(platform)) return [["accessToken", platform === "GITHUB" ? "Personal Access Token" : "Access Token"]];
  return [["cookie", "Cookie"]];
}
function selectAccount(platform) {
  activeAccount = platform;
  $("account-title").textContent = names[platform];
  document.querySelectorAll("#account-list button").forEach(b => b.classList.toggle("selected", b.dataset.platform === platform));
  $("credential-fields").replaceChildren();
  for (const [key, title] of credentialFields(platform)) {
    const label = element("label", "", title); label.htmlFor = "credential-" + key;
    const input = element("input"); input.id = label.htmlFor; input.name = key;
    input.type = "password"; input.autocomplete = "off"; input.maxLength = 65536;
    $("credential-fields").append(label, input);
  }
  if (platform === "XUNLEI") {
    const label = element("label", "", "登录通道"); label.htmlFor = "credential-authType";
    const input = element("select"); input.id = label.htmlFor;
    for (const [value, name] of [["", "App"], ["webToken", "网页"]]) {
      const option = element("option", "", name); option.value = value; input.append(option);
    }
    $("credential-fields").append(label, input);
  }
}
async function loadAccounts() {
  accountStates = await api("accounts"); $("account-list").replaceChildren();
  for (const account of accountStates.filter(a => !["ILANZOU", "LANZOU"].includes(a.platform))) {
    const el = element("button", "", names[account.platform]); el.type = "button"; el.dataset.platform = account.platform;
    const dot = element("span", "account-dot" + (account.configured ? " saved" : ""));
    dot.title = account.configured ? "已保存凭证" : "未配置"; el.append(dot);
    el.addEventListener("click", () => selectAccount(account.platform)); $("account-list").append(el);
  }
  selectAccount(activeAccount);
}
document.querySelectorAll("nav button").forEach(b => b.addEventListener("click", () => view(b.dataset.view)));
$("resolve-form").addEventListener("submit", e => { e.preventDefault(); busy(e.submitter, () => resolve($("link").value, $("password").value)); });
$("back").addEventListener("click", () => busy($("back"), async () => {
  const parent = directoryStack[directoryStack.length - 2];
  if (!parent) return;
  const result = await api("files", { sessionId, directory: parent.id });
  directoryStack.pop(); renderFiles(result);
}));
$("direct-form").addEventListener("submit", e => { e.preventDefault(); busy(e.submitter, async () => {
  await api("direct", { url: $("direct-url").value, filename: $("direct-name").value });
  $("direct-form").reset(); notify("已加入下载"); view("tasks");
}); });
$("refresh").addEventListener("click", () => busy($("refresh"), refreshTasks));
$("account-form").addEventListener("submit", e => { e.preventDefault(); busy(e.submitter, async () => {
  const credentials = Object.fromEntries(credentialFields(activeAccount).map(([key]) => [key, $("credential-" + key).value.trim()]).filter(([, value]) => value));
  if (!Object.keys(credentials).length) throw new Error("请填写凭证");
  if (activeAccount === "XUNLEI") credentials.authType = $("credential-authType").value;
  await api("accounts", { platform: activeAccount, credentials });
  await loadAccounts(); notify("凭证已保存");
}); });
$("clear-account").addEventListener("click", () => busy($("clear-account"), async () => {
  await api("accounts", { platform: activeAccount, credentials: {} });
  await loadAccounts(); notify("凭证已清除");
}));
icons();
refreshTasks().catch(e => notify(e.message, true));
setInterval(() => { if (!document.hidden) refreshTasks().catch(() => {}); }, 2000);
