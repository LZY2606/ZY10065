# OpenAPI 兼容性分析平台（openapi-compat-check）

在接口上线前，判断一组 OpenAPI 变更如何影响既有调用方：用户创建**基线规范**与**候选规范**，
导入**脱敏的请求/响应样本**，系统解析引用、`allOf/oneOf/anyOf` 组合、判别器和参数继承，
给出结构变化、逐样本的旧/新验证结果，以及响应消费方可能看到的具体差别。

技术栈：Spring Boot 3.5、Java 17 字节码（本机 JDK 24 可运行）、H2 文件数据库、原生静态页面（无 Node 构建）。

## 运行

需要 JDK 17+（已在 JDK 24 上验证）。

```bash
# 安装/打包（跳过测试）
./mvnw -q -DskipTests package

# 演示：先跑自动化用例，再在固定端口启动
./mvnw -q test && ./mvnw -q spring-boot:run -Dspring-boot.run.arguments=--server.port=5202
```

页面固定在 <http://127.0.0.1:5202> 。数据写入 `./data/` 下的 H2 文件；删除该目录即回到空库。

## 页面结构

- **导入**：导入基线/候选规范（JSON 或 YAML）、导入脱敏样本集合、创建排练。
- **排练汇总**：破坏性/非破坏性计数、通过率、独立证据（不可解析引用、循环 schema、不完整样本）、
  结构变化表；点击样本行可下钻。
- **单样本下钻**：旧验证、新验证（请求与响应分开），以及“响应消费方可能看到的差别”。
- **豁免与决策**：有时限豁免的授予/撤销；自动显示 `ACTIVE / PENDING / EXPIRED / REVOKED`。
- **审计事件**：只追加的人工决策台账（理由、操作者、前后版本）。

## HTTP API（摘要）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/specs` | 幂等导入规范，body 为 `{name, role(baseline|candidate), mediaType, content}` |
| GET | `/api/specs` / `/api/specs/{id}` | 列出/查看规范 |
| POST | `/api/sample-batches` | 原子导入样本集合 `{name, samples:[...]}`；重复内容返回既有集合 |
| GET | `/api/sample-batches` | 列出样本集合 |
| POST | `/api/runs` | 创建排练 `{baselineId, candidateId, sampleBatchId, policyVersion}` |
| GET | `/api/runs/{id}/report?asOfMs=` | 报告（findings、evidence、samples、summary、豁免、决策） |
| GET | `/api/runs/{id}/samples/{key}` | 单样本下钻 |
| POST | `/api/runs/{id}/exemptions` | 授予有时限豁免（findingId、actor、reason、validFromMs、validToMs） |
| POST | `/api/exemptions/{id}/revoke` | 撤销（产生新事件，不删历史） |
| POST | `/api/exemptions/{id}/reconcile?candidateSpecId=` | 把豁免对更新后的候选重新定位，返回 ACTIVE/PENDING |
| POST | `/api/runs/{id}/decisions` | 对单个变化做人工判定（ACCEPTED/REJECTED，记录理由与操作者） |
| GET | `/api/events?runId=` | 只追加事件流 |
| GET | `/api/runs/policies` | 已知兼容策略版本 |

## 兼容性判定语义（compat-1.0，compat-1.1）

方向由 schema 所在的侧决定（请求 vs 响应），同一种变化在两侧含义相反：

- **请求侧破坏**：新增必填参数/字段、参数或字段变为必填、枚举收窄（旧值不再合法）、
  `minimum` 上调 / `maximum` 下调（旧请求可能越界）、`minLength` 上调 / `maxLength` 下调、
  请求媒体类型移除、请求体变为必填。
- **响应侧破坏**：字段移除、字段变为必填、枚举**放宽**（旧消费方可能不认识新值）、
  新增必填响应字段、`minimum` 下调 / `maximum` 上调（旧的范围假设失效）、状态码移除、
  响应媒体类型移除、响应头变为可选。
- **始终破坏**：操作移除、类型变化、可空性收紧、组合分支移除、判别器变化/映射移除、
  `additionalProperties` 由开放变封闭、数字/长度边界越界方向的调整（按侧判定）。
- **非破坏/提示**：新增可选字段、新增操作/状态码/媒体类型、默认值变化、格式变化；
  `compat-1.1` 把默认值/格式变化降为 `INFO`，并把请求侧 `pattern` 收紧视为破坏。

状态码匹配遵循**精确状态码 → `4XX/5XX` 范围 → `default`** 的优先级；走 `default`
兜底会在样本证据中显式标注。

## 核心不变量

1. **可复现**：所有结论是 `(保存的基线, 保存的候选, 保存的样本集合快照, 策略版本)` 的纯函数。
   指纹一律是对**规范化 JSON**（递归排序键、无无关空白）取 SHA-256；YAML/JSON 差异、
   键遍历顺序不会改变结果。报告中的 finding 按定位与变更内容排序，id 为稳定的 `F0001...`。
2. **排练身份即缓存键**：一次排练由“基线指纹 + 候选指纹 + 样本集合快照指纹 + 策略版本”
   共同决定（四者哈希为 `run_fingerprint`）。两个候选版本并行存在时候选指纹不同，
   不可能命中同一份报告；重复创建返回既有排练（`deduplicated=true`）。
3. **证据独立，绝不计入通过率**：无法解析的引用、循环 schema、不完整样本
   （缺状态码/缺必填请求体等）与未匹配样本单独展示，标记为 `EVIDENCE_ONLY`，
   通过率分母只包含可完整判定的样本：
   `passRate = 通过 / (总样本 - 证据隔离样本)`。
4. **幂等导入，失败不留部分结果**：规范与样本集合按内容指纹去重；样本集合内重复 `key`
   或重复内容会整体回滚（批量导入是单个事务）。
5. **豁免靠结构定位，不靠字符串相似**：豁免绑定到明确的方法+路径+结构定位（如
   `POST /orders#responses/200/content/application/json/schema/properties/customer/properties/vip`）
   与候选指纹。候选继续演变导致定位失效（字段/参数/媒体类型/状态码消失、引用不可解析、
   进入循环）时，对新版本重新解析得到 `PENDING`，并给出原因；不存在名称相似的自动续绑。
   豁免有时限：`NOT_STARTED / ACTIVE / EXPIRED / REVOKED` 为纯函数计算（默认以排练创建时刻评估）。
6. **人工决策可审计**：授予、撤销、逐变化判定都写入只追加的 `event` 表，记录理由、操作者、
   前后版本指纹与时间；撤销是新事件，历史永不删除。
7. **与当前时间解耦**：时间只影响审计记录的 `created_at_ms` 与豁免有效期评估，
   不影响结构差异与样本结论；报告视图支持显式 `asOfMs` 重放历史状态。

## 测试数据含义（`src/test/resources/testdata/`）

- `baseline.json`：订单 API 基线。含组件引用（`#/components/schemas/...`）、路径级参数继承
  （`X-Tenant`）、`allOf` 之外的对象组合、枚举、数字与字符串边界、可空字段、`default`。
- `candidate.json`：常规候选，刻意覆盖：新增必填查询参数 `trace`、查询枚举收窄、
  `limit.maximum` 从 100 收到 50 且默认值变化、响应 `amount` 从 `number` 变 `integer` 且
  上限下调、`Order.state` 枚举收窄、移除 `coupon` 字段、新增必填 `region`、
  `Customer` 新增必填 `vip` 且 `tier` 枚举放宽、删除 `404` 与 `default` 响应、新增 `/health`。
- `candidate-broken.json`：外部 `$ref`（`https://.../External`，不做网络抓取）与
  `LoopA/LoopB` 互相引用形成的循环，用于验证证据隔离。
- `candidate-v2.json`：在 candidate 基础上移除 `trace` 参数，用于验证豁免定位失效→PENDING。
- `samples.json`：脱敏样本集合。
  - `list-closed-ok`：基线上合法、候选上因枚举收窄/新增必填字段/类型收紧而失败（具体命中）。
  - `list-new-ok`：基线上合法，候选上仅新增必填字段失败。
  - `get-order-over-limit`：金额 950000 在基线上合法、候选上限 900000 下越界。
  - `create-valid`：旧请求带 `state=closed`，候选枚举收窄后请求失败（请求侧证据）。
  - `incomplete-no-status`：缺状态码与响应体 → 不完整样本（证据，不进通过率）。
  - `unknown-route`：`DELETE /orders/{id}` 不存在 → 未匹配样本（证据，不进通过率）。

## 自动化用例

- `DeterminismTest`：键序/格式/YAML 等价不改变指纹。
- `EngineTest`：引用解析与参数继承；required/nullable/默认值/枚举/数字边界/媒体类型/状态码优先级
  的差异检测；两个策略版本只改级别不改变化集合；循环与外部引用成为独立证据；样本校验。
- `PlatformIntegrationTest`（Spring Boot + MockMvc，H2 内存库）：导入幂等、失败批次原子回滚、
  四指纹缓存与并行候选不串缓存、证据样本不计入通过率、豁免授予/过期/撤销事件、
  候选继续演变导致豁免 PENDING（不依赖字符串相似）。

## 目录

```
src/main/java/com/example/compat/
  engine/   规范解析、引用/循环处理、schema 扁平化、差异引擎、样本校验、报告组装、策略版本
  service/  幂等导入、排练缓存、豁免定位与事件溯源
  repo/     JdbcTemplate 持久层
  web/      REST 控制器
src/main/resources/static/  原生 HTML/CSS/JS 页面
src/test/                   单元与端到端用例、测试数据
```
