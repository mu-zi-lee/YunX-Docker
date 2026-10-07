import http from "node:http";

const data = Buffer.alloc(4 * 1024 * 1024);
for (let i = 0; i < data.length; i++) data[i] = i % 251;
const server = http.createServer((request, response) => {
  const match = /^bytes=(\d+)-(\d*)$/.exec(request.headers.range || "");
  const start = match ? Number(match[1]) : 0;
  const end = match && match[2] ? Math.min(Number(match[2]), data.length - 1) : data.length - 1;
  response.writeHead(match ? 206 : 200, {
    "Content-Type": "application/octet-stream",
    "Content-Length": end - start + 1,
    ...(match ? { "Content-Range": `bytes ${start}-${end}/${data.length}` } : {}),
  });
  let offset = start;
  const timer = setInterval(() => {
    const next = Math.min(offset + 8192, end + 1);
    response.write(data.subarray(offset, next));
    offset = next;
    if (offset > end) {
      clearInterval(timer);
      response.end();
    }
  }, 40);
  response.on("close", () => clearInterval(timer));
});
server.listen(0, "127.0.0.1", () => console.log(`PORT=${server.address().port}`));
process.stdin.on("data", () => server.close());
