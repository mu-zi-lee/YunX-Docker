import assert from "node:assert/strict";
import { createServer } from "node:http";
import { spawn } from "node:child_process";
import { once } from "node:events";
import { mkdir, readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { setTimeout as delay } from "node:timers/promises";

const directory = resolve(".test-data/restart-" + Date.now());
await mkdir(directory, { recursive: true });
const sourceData = Buffer.alloc(16 * 1024 * 1024);
for (let i = 0; i < sourceData.length; i++) sourceData[i] = i % 251;
const source = createServer((req, res) => {
  const match = /^bytes=(\d+)-(\d*)$/.exec(req.headers.range || "");
  let position = match ? Number(match[1]) : 0;
  const end = match && match[2] ? Math.min(Number(match[2]), sourceData.length - 1) : sourceData.length - 1;
  const headers = { "Content-Length": end - position + 1, "Accept-Ranges": "bytes" };
  if (match) headers["Content-Range"] = `bytes ${position}-${end}/${sourceData.length}`;
  res.writeHead(match ? 206 : 200, headers);
  const timer = setInterval(() => {
    const next = Math.min(end + 1, position + 64 * 1024);
    res.write(sourceData.subarray(position, next)); position = next;
    if (position > end) { clearInterval(timer); res.end(); }
  }, 30);
  res.on("close", () => clearInterval(timer));
});
await new Promise(done => source.listen(0, "127.0.0.1", done));
const portProbe = createServer();
await new Promise(done => portProbe.listen(0, "127.0.0.1", done));
const port = portProbe.address().port;
await new Promise(done => portProbe.close(done));
const url = `http://127.0.0.1:${port}`;
let password;
let auth;
let child;
let log = "";
async function api(path, body) {
  const response = await fetch(url + "/api/" + path, {
    headers: { Authorization: auth, ...(body ? { "Content-Type": "application/json", "X-YunX-Request": "1" } : {}) },
    ...(body ? { method: "POST", body: JSON.stringify(body) } : {})
  });
  const data = await response.json();
  assert.equal(response.status, 200, JSON.stringify(data));
  return data;
}
async function start() {
  child = spawn(resolve("server/build/install/yunx-server/bin/yunx-server"), [], {
    env: { ...process.env, YUNX_PASSWORD: "", YUNX_HOST: "127.0.0.1", YUNX_PORT: String(port),
      YUNX_THREADS: "2", YUNX_DESKTOP_DATA_DIR: directory + "/data", YUNX_DOWNLOAD_DIR: directory + "/downloads",
      JAVA_OPTS: `-Xmx256m -Djava.util.prefs.userRoot=${directory}/preferences` },
    stdio: ["ignore", "pipe", "pipe"]
  });
  child.stdout.on("data", chunk => { log = (log + chunk).slice(-20000); });
  child.stderr.on("data", chunk => { log = (log + chunk).slice(-20000); });
  for (let i = 0; i < 100; i++) {
    if (child.exitCode !== null) throw new Error(log);
    try {
      if ((await fetch(url + "/health")).ok) {
        const saved = (await readFile(directory + "/data/initial-password.txt", "utf8")).trim();
        assert.match(saved, /^[A-Za-z0-9_-]{32}$/);
        if (password) assert.ok(saved === password, "Password must persist across restarts");
        password = saved;
        auth = "Basic " + Buffer.from("admin:" + password).toString("base64");
        assert.ok(!log.includes(password), "Logs must not expose the login password");
        return;
      }
    } catch (error) { if (error.code !== "ECONNREFUSED" && error.name !== "TypeError") throw error; }
    await delay(100);
  }
  throw new Error("Server startup timeout: " + log);
}
async function stop(signal = "SIGTERM") {
  if (!child || child.exitCode !== null || child.signalCode) return;
  const exited = once(child, "exit");
  child.kill(signal);
  await Promise.race([exited, delay(20000, undefined, { ref: false }).then(() => { throw new Error("Server did not stop"); })]);
  child = undefined;
}
try {
  await start();
  await api("accounts", { platform: "QUARK", credentials: { cookie: "restart-test-cookie" } });
  const { id } = await api("direct", { url: `http://127.0.0.1:${source.address().port}/file`, filename: "restart.bin" });
  let progress = 0;
  for (let i = 0; i < 100 && progress === 0; i++) {
    await delay(50);
    progress = (await api("tasks")).find(task => task.id === id).downloaded;
  }
  assert.ok(progress > 0, "Download must make progress before restart");
  await stop();
  await start();
  const restored = (await api("tasks")).find(task => task.id === id);
  assert.equal(restored.status, 2);
  assert.ok(restored.downloaded > 0, "Progress must persist across restart");
  assert.equal((await api("accounts")).find(account => account.platform === "QUARK").configured, true);
  assert.ok(!(await readFile(directory + "/data/server-credentials.enc", "utf8")).includes("restart-test-cookie"));
  await api("tasks", { id, action: "resume" });
  let task;
  for (let i = 0; i < 600; i++) {
    task = (await api("tasks")).find(task => task.id === id);
    assert.notEqual(task.status, 4, task.error);
    if (task.status === 3) break;
    await delay(50);
  }
  assert.equal(task.status, 3, "Resumed download must complete");
  assert.deepEqual(await readFile(task.savePath), sourceData);
  await stop();
  // Verify startup also handles an abruptly interrupted task.
  await start();
  const next = await api("direct", { url: `http://127.0.0.1:${source.address().port}/file`, filename: "forced-restart.bin" });
  await delay(250);
  await stop("SIGKILL");
  await start();
  assert.equal((await api("tasks")).find(t => t.id === next.id).status, 2);
  console.log("Graceful/forced restart and resumed file verification passed.");
} catch (error) {
  console.error(log);
  throw error;
} finally {
  await stop();
  await new Promise(done => source.close(done));
}
