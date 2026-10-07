# Docker 服务器版

服务器版提供浏览器界面，复用桌面版 Kotlin 网盘 API、分享解析仓库、SQLite 和下载器。
下载发生在服务器，关闭浏览器后继续运行。无需 Windows、图形桌面、Chromium 或 C++ 编译器。

## 部署

服务器安装 Docker Engine 和 Docker Compose v2。在本仓库根目录执行：

```sh
mkdir -p downloads
sudo chown 1000:1000 downloads
docker compose up -d
docker compose exec -T yunx cat /data/initial-password.txt
```

Compose 从 Docker Hub 拉取 `muzileee/yunx-server:latest`，无需在服务器编译。
首次启动自动生成 32 字符随机密码，保存到 `/data/initial-password.txt`（权限 `600`），
不会写入容器日志。重启、重新创建容器和更新镜像时，只要保留 `/data`，密码就保持不变。
默认用户名为 `admin`。上面的最后一行读取登录密码，NAS 也可在容器终端执行
`cat /data/initial-password.txt`。

需要自定义端口、用户名或密码时，复制 `.env.example` 为 `.env` 后编辑。
`YUNX_PASSWORD` 留空即可自动生成；填写时使用 12–256 字符，显式配置优先于保存的初始密码，
移除显式配置后恢复之前保存的网页登录密码；如果从未保存过，则首次生成随机密码。

从 v0.2.0 开始使用 YunX 自带的登录页面，不再弹出浏览器 HTTP Basic 登录框。
登录后，在「设置 → 登录与安全」可修改密码。新密码以 PBKDF2 哈希保存到
`/data/login-auth.json`，重启继续有效；修改后所有旧会话失效，初始明文密码文件会删除。
如果设置了 `YUNX_PASSWORD`，密码由该部署变量管理，网页不能修改。
忘记网页修改后的密码时，停止容器、备份数据并删除 `data/login-auth.json`，
保持 `YUNX_PASSWORD` 为空后重新启动，读取重新生成的初始密码。不要删除其他数据。

容器以 UID/GID `1000:1000` 运行。使用其他下载目录时，请先创建该目录并授权该用户写入；
目录归属的调整应只针对专门的下载目录。NAS 可通过 ACL 授予 UID 1000 写入权限。

浏览器打开 `http://服务器IP:8080`，用上述用户名和密码登录。
页面的「账号」可添加凭证、刷新昵称与容量，已配置账号与待添加平台分开显示；
「我的网盘」可浏览个人目录；「分享解析」可浏览分享目录、保存收藏和打开历史；
两个文件浏览器均支持当前目录搜索、排序、列表/网格切换和逐文件多选下载。
「下载」可查看进度、暂停、继续或移除任务记录。移除任务记录不会删除已完成文件。
已完成文件直接保存在宿主机下载目录，不经过浏览器。

公网使用时，将服务放在 HTTPS 反向代理后保护密码和网盘凭证；
反向代理须转发 Cookie/Set-Cookie，正确设置 `X-Forwarded-Proto: https`。
长时间的解析/取链请求建议设置至少 180 秒超时。
使用本机反向代理时，可将 `YUNX_BIND_ADDRESS` 设置为 `127.0.0.1`。

### NAS 部署

将本仓库的 `docker-compose.nas.yml` 保存为 NAS 项目的 Compose 配置。
在同一目录创建 `data`、`downloads`，授予运行用户写入权限，然后通过 NAS 的容器管理界面启动。
无需填写密码。默认 UID/GID 为 `1000:1000`，如 NAS 用户不同，可设置 `APP_UID`、`APP_GID`，
并授权这两个目录给相应用户。不要对已有共享目录递归改归属。

使用 SSH 部署时：

```sh
mkdir -p data downloads
sudo chown 1000:1000 data downloads
docker compose -f docker-compose.nas.yml up -d
docker compose -f docker-compose.nas.yml exec -T yunx cat /data/initial-password.txt
```

账号、密码和任务保存到项目的 `data` 目录，下载文件保存在 `downloads`。
升级前备份这两个目录。默认使用 `latest`；要固定版本，设置
`YUNX_IMAGE=muzileee/yunx-server:0.2.0`。镜像支持 x86_64（amd64）与 ARM64，32 位 ARM 不在支持范围。

## 配置

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `YUNX_USERNAME` | `admin` | 网页登录用户名，不能含冒号 |
| `YUNX_PASSWORD` | 自动生成 | 可留空；自定义时 12–256 字符 |
| `YUNX_IMAGE` | `muzileee/yunx-server:latest` | 要拉取的镜像或固定版本 |
| `YUNX_PORT` | `8080` | 宿主机端口 |
| `YUNX_BIND_ADDRESS` | `0.0.0.0` | 宿主机监听地址 |
| `YUNX_DOWNLOAD_PATH` | `./downloads` | 宿主机下载目录 |
| `YUNX_THREADS` | `16` | 每个任务的下载线程数，1–128 |
| `YUNX_CONCURRENCY` | `3` | 同时下载任务数，1–10 |
| `YUNX_SPEED_LIMIT` | `0` | 总限速，单位字节/秒；0 不限速 |
| `TZ` | `Asia/Shanghai` | 容器时区 |

容器端口固定为 8080，Compose 的 `YUNX_PORT` 只修改宿主机映射。
默认内存上限为 1 GB，JVM 使用至多约 60% 作为堆。首次编译建议至少有 3 GB 可用内存。
GitHub CI 在 amd64、arm64 原生 runner 上分别构建、运行容器并验证 NAS 持久化。
「设置」中保存的线程数、并发数和限速会持久化并优先于环境变量默认值。
线程设置对新启动任务生效，调整并发后继续或新增任务时调度器会按新上限运行。

## 网盘凭证与功能范围

| 平台 | 网页账号配置 |
| --- | --- |
| 夸克、UC、百度、139、115 | 手动粘贴 Cookie |
| 123、光鸭 | 手动粘贴 Access Token |
| 迅雷 | Access Token、Refresh Token、Device ID、Captcha Token，选择 App 或网页通道 |
| GitHub | 可选 Personal Access Token |
| 蓝奏云、蓝奏优享 | 当前网页使用游客分享解析与下载 |

凭证字段不会回显。「已保存凭证」仅表示已保存，不代表上游账号已验证；
遇到登录过期时，重新填写凭证。夸克/UC 会回写 API 响应更新的 Cookie，迅雷使用 Refresh Token
按选择的通道刷新令牌。网页不提供桌面端的浏览器 Cookie 导入、内嵌登录、短信登录或验证码交互。
需要上游验证码/风控确认时，先在官方客户端处理。

支持逐文件下载、分享目录浏览、HTTP/HTTPS 直链下载，以及 GitHub 仓库 Releases、
默认分支 ZIP 和账号仓库列表。GitHub 仓库链接中的 tree/blob 子路径当前按仓库入口处理。
个人网盘浏览与下载支持夸克、UC、百度、139、115、123、光鸭与迅雷；
迅雷个人目录需 App 通道凭证，网页通道仍可用于既有分享解析流程。
光鸭容量查询还需 Device ID 与 Device Sign；页面中可填写。
未配置或接口不支持容量时显示未知，不会编造容量或把一次网络失败判定成 Cookie 过期。
服务器版没有桌面托盘、系统通知、剪贴板检测、云端文件移动/删除/重命名、
整文件夹递归下载或 Gopeed/磁力下载入口。功能对照见 `docs/WEB-FEATURES.md`。
大文件签名直链到期后，继续旧任务可能失败，请重新解析分享获取直链。

## 数据与更新

`yunx-data` 命名卷挂载到 `/data`，包含：

- SQLite 任务记录、加密下载请求头；
- `server-credentials.enc` 加密账号凭证；
- `credential.key` 加密密钥，必须与数据一起备份；
- `initial-password.txt` 自动生成的登录密码，需作为敏感文件保管；
- `login-auth.json` 登录密码哈希，修改密码后初始密码文件会删除；
- 收藏和解析历史（含分享提取码），以及持久化下载设置；
- 下载分片、日志及 Java Preferences。

下载目录单独挂载到 `/downloads`。备份时先执行 `docker compose stop`，
再备份整个数据卷和宿主机下载目录，避免数据库/分片仍在写入。
不要只备份数据库而漏掉密钥和分片。密钥与密文共同保存在同一个卷中，
加密用于避免明文凭证落盘，不能替代服务器访问控制。

```sh
# 拉取更新并启动，保留原有密码、凭证和任务
docker compose pull
docker compose up -d

# 关闭容器，保留数据
docker compose down

# 检查状态
docker compose ps
docker compose logs --tail=100 yunx
```

重启后，中断的任务会显示为「已暂停」，点击继续后使用保留分片续传。
正常停止会等待暂停完成；强制终止后由下次启动按现有分片恢复。
不要执行 `docker compose down -v`，它会删除命名数据卷。

## 本地开发与验证

首次 Docker Hub 发布前，或需要自行构建时：

```sh
docker compose -f compose.yaml -f compose.build.yaml up -d --build
```

服务器模块拥有独立 Gradle 构建，不会解析或打包 Compose/JCEF 桌面依赖：

```sh
gradle -p server test installDist
YUNX_PASSWORD='local-development-password' \
YUNX_DESKTOP_DATA_DIR="$PWD/.test-data/server" \
YUNX_DOWNLOAD_DIR="$PWD/downloads" \
JAVA_OPTS="-Djava.util.prefs.userRoot=$PWD/.test-data/preferences" \
server/build/install/yunx-server/bin/yunx-server
```

使用 JDK 17 或更高版本，Docker 构建采用 Gradle 8.14.3 / JDK 21。
测试覆盖首次密码生成、文件权限、密码持久化、认证、跨站写入拦截、请求大小上限、凭证加密持久化和实际 Range 下载暂停续传，
以及复用核心的链接识别、路径安全、Range/HLS 请求策略测试。
这些测试使用本地 HTTP 模拟源，不代表真实网盘账号、上游风控或服务器网络验收。

## GitHub 自动发布

在 YunX 仓库 Settings → Secrets and variables → Actions 添加两个 Repository secrets：

- `DOCKERHUB_USERNAME`：`muzileee`；
- `DOCKERHUB_TOKEN`：Docker Hub 的 Access Token，需要该镜像仓库的写权限。

其他 GitHub 仓库已有的 Secret 不能直接在本仓库读取。Token 不写入源码、Compose 或镜像。
服务器版本独立记录在 `server/version.txt`。提交并推送相应标签（例如 `server-v0.1.0`）
触发发布，或从 Actions 手动运行 `Publish server image`。
流程先执行双架构容器测试，再发布架构镜像并合并版本标签和 `latest`。
版本号必须与标签一致；常规推送 `main` 只做 CI，不发布镜像。

本项目沿用 AGPL-3.0。对外提供修改后的网络服务时，需要按协议提供对应源代码。
