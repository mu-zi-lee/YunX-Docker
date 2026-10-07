import assert from "node:assert/strict";
import { createServer } from "node:http";
import { mkdir, readFile } from "node:fs/promises";
import { resolve } from "node:path";

const { chromium } = await import(process.env.PLAYWRIGHT_MODULE || "playwright");
const baseURL = process.env.YUNX_TEST_URL || "http://127.0.0.1:18080";
const password = process.env.YUNX_TEST_PASSWORD || (await readFile(".test-data/redesign/data/initial-password.txt","utf8")).trim();
const output = resolve(".test-data/redesign/browser");
await mkdir(output,{recursive:true});
const data = Buffer.alloc(512*1024,73);
const source = createServer((req,res) => {
  const range = /^bytes=(\d+)-(\d*)$/.exec(req.headers.range || "");
  const start = range ? Number(range[1]) : 0, end = range && range[2] ? Number(range[2]) : data.length-1;
  res.writeHead(range ? 206 : 200, {"Content-Length":end-start+1,"Accept-Ranges":"bytes",...(range ? {"Content-Range":`bytes ${start}-${end}/${data.length}`} : {})});
  res.end(data.subarray(start,end+1));
});
await new Promise(done=>source.listen(0,"127.0.0.1",done));
const browser = await chromium.launch({headless:true,executablePath:process.env.PLAYWRIGHT_EXECUTABLE_PATH});
try {
  for (const width of [1440,768,390]) {
    const context = await browser.newContext({viewport:{width,height:width===390 ? 844 : 960}});
    const page = await context.newPage(), errors = [];
    page.on("pageerror",error=>errors.push(error.message));
    await page.route("**/api/account-info",route=>route.fulfill({json:{nickname:"测试账号",available:true,quota:{used:10737418240,total:107374182400,trash:0}}}));
    const suffix = width+"-"+Date.now();
    const files = [
      {fid:"dir",directoryId:"dir",name:"项目资料",directory:true,size:0},
      {fid:"a",name:"设计方案-"+suffix+".pdf",directory:false,size:data.length,modified:"2026-10-06"},
      {fid:"b",name:"很长的中文文件名称-用来检查手机屏幕排版-"+suffix+".zip",directory:false,size:data.length}
    ];
    await page.route("**/api/cloud/files",route=> {
      const body = route.request().postDataJSON();
      return route.fulfill({json:{sessionId:"browser-fixture",platform:"QUARK",title:"我的网盘",directory:body.directory || "0",
        files:body.directory === "dir" ? [files[1]] : files,hasMore:false}});
    });
    await page.route("**/api/cloud/download",async route=> {
      const file = files.find(f=>f.fid===route.request().postDataJSON().fid);
      const response = await context.request.post(baseURL+"/api/direct",{headers:{"X-YunX-Request":"1"},data:{url:`http://127.0.0.1:${source.address().port}/file`,filename:file.name}});
      assert.equal(response.status(),200);
      await route.fulfill({json:await response.json()});
    });
    await page.goto(baseURL);
    await page.locator("#login-view").waitFor({state:"visible"});
    for (const theme of ["light","dark"]) {
      await page.evaluate(value=>{localStorage.setItem("yunx-theme",value);document.documentElement.dataset.theme=value;},theme);
      await page.screenshot({path:`${output}/${width}-${theme}-login.png`,fullPage:true,animations:"disabled"});
    }
    await page.locator("#login-password").fill("wrong-test-password");
    await page.getByRole("button",{name:"进入工作台",exact:true}).click();
    await page.locator("#login-status.error").waitFor();
    await page.locator("#login-password").fill(password);
    await page.getByRole("button",{name:"进入工作台",exact:true}).click();
    await page.locator("#app").waitFor({state:"visible"});
    await page.locator("nav button[data-view=accounts]").click();
    const configured = await page.locator('.account-card[data-platform="QUARK"]').count();
    if (configured) await page.locator('.account-card[data-platform="QUARK"]').getByRole("button",{name:"更新凭证",exact:true}).click();
    else { await page.locator("#account-add").click(); await page.locator("#account-platform").selectOption("QUARK"); }
    await page.getByLabel("Cookie",{exact:true}).fill("browser-fixture-cookie");
    await page.getByRole("button",{name:"保存账号",exact:true}).click();
    await page.locator("#account-dialog").waitFor({state:"hidden"});
    await page.locator('.account-card[data-platform="QUARK"] .capacity-value').filter({hasText:"10.00 GiB"}).waitFor();
    assert.equal(await page.locator("#account-list .account-card").count(),1);
    await page.locator("#available-list").evaluate(node=>{node.closest("details").open=true;});
    assert.ok(await page.locator("#available-list button").count()>0);
    for (const theme of ["light","dark"]) {
      await page.evaluate(value=>{document.documentElement.dataset.theme=value;},theme);
      await page.screenshot({path:`${output}/${width}-${theme}-accounts.png`,fullPage:true,animations:"disabled"});
      assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
    }
    await page.locator("nav button[data-view=cloud]").click();
    await page.locator("#cloud-browser .file-row").first().waitFor();
    await page.locator("#cloud-browser").getByRole("button",{name:"项目资料",exact:true}).click();
    await page.locator("#cloud-browser .breadcrumbs").getByRole("button",{name:"项目资料",exact:true}).waitFor();
    assert.equal(await page.locator("#cloud-browser .file-row").count(),1);
    await page.locator("#cloud-browser .breadcrumbs").getByRole("button",{name:"我的网盘",exact:true}).click();
    await page.waitForFunction(()=>document.querySelectorAll("#cloud-browser .file-row").length===3);
    await page.getByRole("searchbox",{name:"搜索当前目录"}).fill("设计");
    assert.equal(await page.locator("#cloud-browser .file-row").count(),1);
    await page.getByRole("searchbox",{name:"搜索当前目录"}).fill("");
    await page.getByRole("button",{name:"网格视图",exact:true}).click();
    assert.equal(await page.locator("#cloud-browser .file-grid .file-row").count(),3);
    await page.getByRole("button",{name:"列表视图",exact:true}).click();
    await page.getByRole("checkbox",{name:"选择当前显示的文件",exact:true}).check();
    await page.getByRole("button",{name:"下载所选文件",exact:true}).click();
    await page.locator("#notice").filter({hasText:"已加入 2 个下载"}).waitFor();
    for (const theme of ["light","dark"]) {
      await page.evaluate(value=>{document.documentElement.dataset.theme=value;},theme);
      await page.screenshot({path:`${output}/${width}-${theme}-cloud.png`,fullPage:true,animations:"disabled"});
      assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
    }
    await page.locator("nav button[data-view=tasks]").click();
    for (const file of files.filter(f=>!f.directory)) {
      const row = page.locator(".task-row").filter({hasText:file.name});
      await row.locator(".complete").waitFor({timeout:20000});
      const path = await row.locator(".metadata").last().textContent();
      assert.deepEqual(await readFile(path),data);
      await row.getByRole("button",{name:"移除任务记录",exact:true}).click();
      await row.waitFor({state:"detached",timeout:10000});
    }
    // The poller must refresh controls while the focused button changes state.
    let status = 1;
    await page.route("**/api/tasks",async route=> {
      if (route.request().method()==="POST") {
        status=route.request().postDataJSON().action==="pause" ? 2 : 1;
        await route.fulfill({json:{ok:true}});
      } else await route.fulfill({json:[{id:999,filename:"断点续传测试.bin",status,downloaded:524288,total:1048576,speed:102400,mergePercent:-1,error:"",savePath:""}]});
    });
    await page.getByRole("button",{name:"刷新",exact:true}).click();
    await page.getByRole("button",{name:"暂停",exact:true}).click();
    await page.getByRole("button",{name:"继续下载",exact:true}).waitFor({timeout:10000});
    await page.getByRole("button",{name:"继续下载",exact:true}).click();
    await page.getByRole("button",{name:"暂停",exact:true}).waitFor({timeout:10000});
    await page.screenshot({path:`${output}/${width}-tasks.png`,fullPage:true});
    await page.unroute("**/api/tasks");
    await page.locator("nav button[data-view=settings]").click();
    await page.getByLabel("每个任务的线程数",{exact:true}).fill("8");
    await page.getByRole("button",{name:"保存设置",exact:true}).click();
    await page.locator("#settings-status").filter({hasText:"下载设置已保存"}).waitFor();
    assert.equal((await (await context.request.get(baseURL+"/api/settings")).json()).threads,8);
    await page.getByLabel("当前密码",{exact:true}).fill("wrong-test-password");
    await page.getByLabel("新密码",{exact:true}).fill("browser-test-new-password");
    await page.getByLabel("确认新密码",{exact:true}).fill("browser-test-new-password");
    await page.getByRole("button",{name:"修改密码",exact:true}).click();
    await page.locator("#password-status.error").waitFor();
    await page.screenshot({path:`${output}/${width}-settings.png`,fullPage:true});
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
    await page.locator("nav button[data-view=resolve]").click();
    await page.getByLabel("分享链接",{exact:true}).fill("not-a-share");
    await page.locator("#resolve-form").getByRole("button",{name:"解析",exact:true}).click();
    await page.locator("#notice.error").waitFor();
    await page.screenshot({path:`${output}/${width}-resolve.png`,fullPage:true});
    await page.locator("nav button[data-view=accounts]").click();
    await page.locator('.account-card[data-platform="QUARK"]').getByRole("button",{name:"移除账号",exact:true}).click();
    await page.locator("#confirm-remove").click();
    await page.waitForFunction(()=>document.querySelectorAll("#account-list .account-card").length===0);
    await page.locator(width<=720 ? "#mobile-logout" : "#logout").click();
    await page.locator("#login-view").waitFor({state:"visible"});
    assert.equal((await context.request.get(baseURL+"/api/tasks")).status(),401);
    assert.deepEqual(errors,[]);
    await context.close();
  }
  console.log("Browser: login, themes, responsive accounts, quota, files, batch download, focus polling, settings and logout passed.",output);
} finally {
  await browser.close();
  await new Promise(done=>source.close(done));
}
