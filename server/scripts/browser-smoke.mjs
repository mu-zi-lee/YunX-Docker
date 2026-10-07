import assert from "node:assert/strict";
import { createServer } from "node:http";
import { mkdir, readFile } from "node:fs/promises";
import { resolve } from "node:path";

const { chromium } = await import(process.env.PLAYWRIGHT_MODULE || "playwright");
const baseURL = process.env.YUNX_TEST_URL || "http://127.0.0.1:18080";
const password = process.env.YUNX_TEST_PASSWORD || "yunx-local-test-only";
const output = resolve(".test-data/browser");
await mkdir(output, { recursive: true });
const data = Buffer.alloc(512 * 1024);
for (let i = 0; i < data.length; i++) data[i] = i % 251;
const source = createServer((req, res) => {
  const range = /^bytes=(\d+)-(\d*)$/.exec(req.headers.range || "");
  const start = range ? Number(range[1]) : 0;
  const end = range && range[2] ? Math.min(Number(range[2]), data.length - 1) : data.length - 1;
  const headers = { "Content-Length": end - start + 1, "Accept-Ranges": "bytes" };
  if (range) headers["Content-Range"] = `bytes ${start}-${end}/${data.length}`;
  res.writeHead(range ? 206 : 200, headers);
  res.end(data.subarray(start, end + 1));
});
await new Promise(done => source.listen(0, "127.0.0.1", done));
const browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH });
try {
  for (const viewport of [{ width: 1280, height: 900 }, { width: 390, height: 844 }]) {
    const context = await browser.newContext({ viewport, httpCredentials: { username: "admin", password } });
    const page = await context.newPage();
    const errors = [];
    page.on("pageerror", e => errors.push(e.message));
    await page.goto(baseURL);
    await page.locator("nav svg").first().waitFor();
    assert.equal(await page.locator(".brand img").evaluate(img => img.complete && img.naturalWidth > 0), true);
    await page.getByRole("button", { name: "账号", exact: true }).click();
    await page.getByLabel("Cookie", { exact: true }).fill("test-cookie-browser-smoke");
    await page.getByRole("button", { name: "保存凭证", exact: true }).click();
    await page.getByRole("status").filter({ hasText: "凭证已保存" }).waitFor();
    assert.equal(await page.getByLabel("Cookie", { exact: true }).inputValue(), "");
    assert.equal(await page.locator('#account-list button[data-platform="QUARK"] .saved').count(), 1);
    await page.screenshot({ path: `${output}/${viewport.width}-accounts.png`, fullPage: true });
    await page.getByRole("button", { name: "清除", exact: true }).click();
    await page.getByRole("status").filter({ hasText: "凭证已清除" }).waitFor();
    await page.getByRole("button", { name: "解析", exact: true }).first().click();
    await page.getByLabel("分享链接", { exact: true }).fill("not-a-share-link");
    await page.locator("#resolve-form").getByRole("button", { name: "解析", exact: true }).click();
    await page.getByRole("status").filter({ hasText: "未识别到分享链接" }).waitFor();
    await page.getByText("添加直链下载", { exact: true }).click();
    await page.getByLabel("下载地址", { exact: true }).fill(`http://127.0.0.1:${source.address().port}/file`);
    const filename = `browser-${viewport.width}-${Date.now()}.bin`;
    await page.getByLabel("文件名", { exact: true }).fill(filename);
    await page.getByRole("button", { name: "加入下载", exact: true }).click();
    const row = page.locator(".task-row").filter({ hasText: filename });
    await row.locator(".complete").waitFor({ timeout: 20000 });
    const path = await row.locator(".metadata").last().textContent();
    assert.deepEqual(await readFile(path), data);
    await page.screenshot({ path: `${output}/${viewport.width}-tasks.png`, fullPage: true });
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth > innerWidth);
    assert.equal(overflow, false, `Page overflow at ${viewport.width}`);
    await row.getByRole("button", { name: "移除任务记录", exact: true }).click();
    await page.getByRole("status").filter({ hasText: "任务记录已移除" }).waitFor();
    // Keep focus on task controls while status changes, without freezing the poller.
    let mockStatus = 1;
    await page.route("**/api/tasks", async route => {
      if (route.request().method() === "POST") {
        mockStatus = route.request().postDataJSON().action === "pause" ? 2 : 1;
        await route.fulfill({ json: { ok: true } });
      } else {
        await route.fulfill({ json: [{ id: 999999, filename: "focus-test.bin", status: mockStatus,
          downloaded: 100, total: 1000, speed: 10, mergePercent: -1, error: "", savePath: "" }] });
      }
    });
    await page.getByRole("button", { name: "刷新", exact: true }).click();
    await page.getByRole("button", { name: "暂停", exact: true }).click();
    await page.getByRole("button", { name: "继续下载", exact: true }).waitFor({ timeout: 10000 });
    await page.getByRole("button", { name: "继续下载", exact: true }).click();
    await page.getByRole("button", { name: "暂停", exact: true }).waitFor({ timeout: 10000 });
    await page.unroute("**/api/tasks");
    assert.deepEqual(errors, []);
    await context.close();
  }
  console.log("Desktop/mobile browser flow passed; screenshots:", output);
} finally {
  await browser.close();
  await new Promise(done => source.close(done));
}
