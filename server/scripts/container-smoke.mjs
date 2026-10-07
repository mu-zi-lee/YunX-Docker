import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { createServer } from "node:http";
import { mkdir, readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { setTimeout as delay } from "node:timers/promises";

const root = resolve(".test-data/container");
const name = "yunx-ci";
const docker = (...args) => execFileSync("docker", args, { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] }).trim();
const compose = (...args) => docker("compose", "-f", "docker-compose.nas.yml", "-p", name, ...args);
process.env.YUNX_IMAGE = "yunx-server:ci";
process.env.YUNX_PASSWORD = "";
process.env.YUNX_PORT = "18080";
process.env.YUNX_BIND_ADDRESS = "127.0.0.1";
process.env.YUNX_THREADS = "2";
await mkdir(root, { recursive: true });
await mkdir("data", { recursive: true });
await mkdir("downloads", { recursive: true });
execFileSync("sudo", ["chown", "1000:1000", "data", "downloads"]);
const content = Buffer.alloc(512 * 1024, 73);
const source = createServer((req, res) => {
  const match = /^bytes=(\d+)-(\d*)$/.exec(req.headers.range || "");
  const start = match ? Number(match[1]) : 0;
  const end = match && match[2] ? Number(match[2]) : content.length - 1;
  res.writeHead(match ? 206 : 200, {
    "Content-Length": end - start + 1, "Accept-Ranges": "bytes",
    ...(match ? { "Content-Range": `bytes ${start}-${end}/${content.length}` } : {})
  });
  res.end(content.subarray(start, end + 1));
});
await new Promise(done => source.listen(0, "0.0.0.0", done));
const url = "http://127.0.0.1:18080";
let auth;
async function ready() {
  for (let i = 0; i < 120; i++) {
    try { if ((await fetch(url + "/health")).ok) return; } catch {}
    await delay(500);
  }
  throw new Error("Container health check timed out");
}
async function api(path, body) {
  const response = await fetch(url + "/api/" + path, {
    headers: { Cookie: auth, ...(body ? { "X-YunX-Request": "1", "Content-Type": "application/json" } : {}) },
    ...(body ? { method: "POST", body: JSON.stringify(body) } : {})
  });
  assert.equal(response.status, 200);
  return response.json();
}
try {
  compose("up", "-d", "--pull", "never");
  await ready();
  assert.equal((await fetch(url)).status, 200);
  assert.equal((await fetch(url + "/api/tasks")).status, 401);
  const id = compose("ps", "-q", "yunx");
  assert.equal(docker("exec", id, "id", "-u"), "1000");
  assert.equal(docker("exec", id, "stat", "-c", "%a", "/data/initial-password.txt"), "600");
  const password = docker("exec", id, "cat", "/data/initial-password.txt");
  assert.ok(/^[A-Za-z0-9_-]{32}$/.test(password));
  assert.ok(!docker("logs", id).includes(password), "Password leaked to container logs");
  async function login() {
    const response = await fetch(url + "/api/auth/login", {
      method: "POST", headers: { "Content-Type": "application/json", "X-YunX-Request": "1" },
      body: JSON.stringify({username: "admin", password})
    });
    assert.equal(response.status, 200);
    auth = response.headers.get("set-cookie").split(";")[0];
  }
  await login();
  assert.equal((await fetch(url + "/api/tasks", { headers: { Cookie: auth } })).status, 200);
  await api("accounts", { platform: "QUARK", credentials: { cookie: "container-test-cookie" } });
  const gateway = docker("inspect", "-f", "{{range .NetworkSettings.Networks}}{{.Gateway}}{{end}}", id);
  const task = await api("direct", { url: `http://${gateway}:${source.address().port}/sample`, filename: "container.bin" });
  let result;
  for (let i = 0; i < 120; i++) {
    result = (await api("tasks")).find(t => t.id === task.id);
    assert.notEqual(result.status, 4, result.error);
    if (result.status === 3) break;
    await delay(250);
  }
  assert.equal(result.status, 3);
  docker("cp", id + ":/downloads/container.bin", root + "/container.bin");
  assert.deepEqual(await readFile(root + "/container.bin"), content);
  compose("down");
  compose("up", "-d", "--pull", "never");
  await ready();
  assert.ok(docker("exec", compose("ps", "-q", "yunx"), "cat", "/data/initial-password.txt") === password);
  assert.equal((await fetch(url + "/api/tasks", {headers: { Cookie: auth }})).status, 401);
  await login();
  assert.equal((await api("accounts")).find(a => a.platform === "QUARK").configured, true);
  assert.equal((await api("tasks")).find(t => t.id === task.id).status, 3);
  console.log("NAS container: generated password, permissions, authentication, download and recreation persistence passed.");
} finally {
  compose("down");
  await new Promise(done => source.close(done));
}
