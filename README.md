# OpenAPI Pair-wise GSB — 兼容性排练平台

在接口上线前，对**基线规范**与**候选规范**做结构 diff，并把差异落到导入的
脱敏请求/响应样本上：不仅是字段增删，`required`、`nullable`、默认值、枚举
收窄、数字边界、媒体类型与状态码优先级都会被验证。结论由三份冻结输入共同
决定，且可以随时用保存的输入重新算出同样的结果。

## 运行

需要 JDK 17+（在 JDK 24 上验证）。无需预装 Maven（仓库自带 `./mvnw`）。

```bash
# 安装（跳过测试打包）
./mvnw -q -DskipTests package

# 自动化用例 + 演示启动
./mvnw -q test
./mvnw -q spring-boot:run -Dspring-boot.run.arguments=--server.port=5202
```

页面固定在 <http://127.0.0.1:5202>（`src/main/resources/static/index.html`）。
数据保存在本机 H2 文件库 `./data/gsb`（演示产物，已在 `.gitignore` 忽略）。

## 一分钟演示（命令行）

```bash
# 1) 导入两份规范（夹具随测试提供）
curl -s -X PUT --data-binary @src/test/resources/fixtures/baseline.yaml \
  -H 'Content-Type: text/plain' http://127.0.0.1:5202/api/specs/baseline
curl -s -X PUT --data-binary @src/test/resources/fixtures/candidate.yaml \
  -H 'Content-Type: text/plain' http://127.0.0.1:5202/api/specs/candidate

# 2) 导入样本集合
curl -s -X POST http://127.0.0.1:5202/api/samples/default \
  -H 'Content-Type: application/json' -d '[
    {"method":"GET","path":"/pets","statusCode":200,
     "responseMediaType":"application/json",
     "responseBody":[{"id":1,"name":"Rex","kind":"dog","status":"available"},
                     {"id":2,"name":"Tweety","kind":"bird","status":"sold"}]},
    {"method":"POST","path":"/pets","statusCode":404,
     "responseMediaType":"application/json",
     "responseBody":{"code":404,"message":"gone","missingPetId":"p-9"}}
  ]'

# 3) 创建/取回排练（幂等：相同输入返回同一 runKey）
curl -s -X POST http://127.0.0.1:5202/api/runs \
  -H 'Content-Type: application/json' \
  -d '{"baselineKey":"baseline","candidateKey":"candidate",
       "policyVersion":"2025.1","sampleSet":"default"}'
```

## API 概览

| 方法与路径 | 含义 |
| --- | --- |
| `PUT /api/specs/{key}` | 上传规范（`text/plain` 原文，或 JSON `{"rawText":...}`）；同内容幂等，改内容替换并 `versionSeq+1` |
| `GET /api/specs` / `GET /api/specs/{key}` | 规范清单 / 原文+指纹 |
| `POST /api/samples/{set}` | 样本批量导入，返回 `received/inserted/skipped/rejected` |
| `GET|DELETE /api/samples/{set}` | 查看 / 删除样本集合 |
| `POST /api/runs` | 由 `{baselineKey,candidateKey,policyVersion,sampleSet}` 创建或取缓存 |
| `GET /api/runs` / `GET /api/runs/{runKey}` | 排练列表 / 完整报告（含逐样本下钻） |
| `POST /api/exemptions` | 授予有时限豁免（精确 method/path/changeCode/pointer + 有效期） |
| `GET /api/exemptions?candidateKey=&status=` | 豁免列表（含派生 `effectiveStatus`） |
| `POST /api/exemptions/{key}/revoke` | 撤销（**追加新事件**） |
| `GET /api/events` | 只追加的人工决定事件流 |

## 系统不变量（测试均有覆盖）

1. **可重算/确定性**：一次排练 = 规范指纹（规范原文的 SHA-256）× 兼容策略版本
   × 样本集合快照（全部去重键排序后哈希）。所有报告 Map 按 key 排序、列表按
   稳定键排序；`reportJson` 不含时间戳或随机量，同样输入与遍历顺序/当前时间
   无关地产生同一 `runKey` 与同一报告字节。
2. **两个候选版本互不污染缓存**：`runKey = sha256(baselineHash,
   candidateHash, policyVersion, sampleSetHash)`，内容寻址；候选内容一变，
   新排练与旧缓存自动分开。
3. **样本导入去重**：去重键由集合、方法、路径、媒体类型、**规范化后的 JSON
   体**、状态码组成（键序/空白差异不产生新样本）；重复导入只计 `skipped`。
4. **失败批次无部分结果**：任一样本行缺方法/路径或字段非法，整批事务回滚，
   返回 `400 BATCH_REJECTED`。
5. **证据独立、不进通过率**：无法解析的引用、循环 schema 只在样本实际触达时
   把该样本标记为 `EXCLUDED` 并写入 `evidence`；不完整样本同理。
   `passRate = pass / evaluated`，排除项永不作分母分子。
6. **豁免不靠相似度**：豁免绑定授予时的候选内容哈希与精确定位四元组。候选
   继续变化导致定位不到当前变化时，派生状态自动变 `PENDING`（另有
   `EXPIRED/REVOKED/ACTIVE`），永不使用字符串相似匹配。
7. **人工决定只追加**：授予/撤销写入 `DecisionEvent`（理由、操作者、前后
   版本哈希）；撤销产生新事件，不删除或改写历史行。
8. **差异方向语义**：请求侧“收紧约束”对既有调用方是破坏（如新增 required、
   枚举收窄、上限变小、参数变必填）；响应侧“移除/放宽消费方可依赖的东西”是
   破坏（字段删除、状态码移除、消费用媒体类型移除）。状态码解析顺序为
   精确码 → `NXX` 通配 → `default`，基线/候选解析层级不同且形态不同时报
   `STATUS_PRIORITY_SHIFT`。

### 兼容策略版本

- `2024.09`（legacy）：响应上“新增 required 字段”降为 `WARNING`；
- `2025.1`（strict，默认）：该变化为 `BREAKING`。
策略版本是排练指纹的一部分，历史报告不会因新策略而改变。

## 测试数据的含义

`src/test/resources/fixtures/{baseline,candidate}.yaml` 是一个 Pets API：
候选相对基线**同时**引入多类变化，用于证明结论不是“字段增删”单维度：

- 响应 `Pet.kind` 枚举 `[dog,cat,bird] → [dog,cat]`：枚举收窄，旧样本里
  `bird` 在候选下失败；
- 响应新增 required `createdAt`：legacy 下 WARNING、strict 下 BREAKING；
- 响应 `tag` 去掉 `nullable: true`：可空性变化；
- 查询参数 `limit` 上限 `100 → 50`：数字边界收紧；
- 头参数 `X-Trace-Id` 由可选变必填（参数继承 + `$ref` 解析）；
- `POST /pets` 错误响应由精确 `404` 变为 `4XX` 通配 + `default`：状态码
  优先级迁移；
- 解析器单测还构造了外部/缺失 `$ref` 与 `A→B→A` 循环 schema。

自动化用例（共 22 个，`./mvnw test`）：

- `SpecParserTest`：`$ref`/allOf/判别器旁的组合解析、参数继承、未解析引用与
  循环 schema 作为证据返回而不抛异常；
- `DifferTest`：枚举、required、nullable、参数必填、媒体类型、状态码优先级；
- `SchemaValidatorTest`：required/nullable/枚举/数字边界、证据只按样本实际
  触达判定、违反列表顺序确定；
- `CanonicalTest`：键序/遍历序不改变哈希；
- `ApiIntegrationTest`：端到端覆盖幂等导入、去重、整批回滚、双策略、下钻、
  豁免 PENDING 化、撤销追加事件、缓存隔离。

## 代码布局

- `com.gsb.engine`：纯计算（`SpecParser`、`SchemaNode`、`SchemaValidator`、
  `Differ`、`Canonical`），无框架依赖，报告可离线复算；
- `com.gsb.service`：事务边界、持久化、策略分类与豁免状态派生；
- `com.gsb.model`：JPA 实体与仓库；
- `com.gsb.web`：REST 控制器与统一错误处理；
- `src/main/resources/static/index.html`：无构建步骤的单页控制台。
