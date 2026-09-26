# cc-telescope

管理观测提案、仪器需求、台站时间，并支持目标机会（Target of Opportunity, ToO）对普通观测计划的受控抢占。

## 主要业务规则

### 普通预订

- **望远镜与仪器**：望远镜登记唯一编号、名称、支持的仪器集合，以及固定的仪器切换准备时长（分钟）。
- **获批提案**：提案登记唯一编号、允许使用的仪器集合和总观测分钟配额；配额随预订扣减、随取消归还。
- **观测预订**：预订关联提案、望远镜、仪器和起止时间（ISO-8601，左闭右开 `[start, end)`，需对齐整分钟）。
  仪器必须同时被望远镜支持且被提案允许；预订时长从提案剩余配额中扣减，配额不足时拒绝。
- **时间冲突**：同一望远镜上的有效预订不得重叠。相邻预订使用不同仪器时，两者之间必须至少留出
  切换准备时长；使用相同仪器时可以首尾相接。新预订会同时检查前、后相邻记录。
- **幂等预订**：每笔预订携带幂等键。相同内容重放返回原预订（HTTP 200），相同幂等键但内容不同返回冲突（HTTP 409）。
- **取消**：仅尚未开始的预订可以取消，取消只归还一次配额（重复取消幂等）；已开始或已结束的预订取消返回冲突。
  被抢占后处于待重排状态的预订可以放弃（取消），但不会再次归还配额（配额在抢占时已归还）。
- **并发**：预订通过望远镜行级悲观锁串行化时间冲突检查，提案配额通过提案行级悲观锁扣减。
  统一的加锁顺序为：望远镜 → 提案（按 ID 排序）→ 预订（按 ID 排序），
  取消、抢占确认与重排并发下不会死锁、不会超卖配额或形成时间冲突。

### 目标机会抢占

- **目标机会提案**：注册提案时设置 `targetOpportunity=true`、`priority`（整数，越大优先级越高）、
  `validUntil`（响应时限/剩余有效期截止时间）和可用分钟配额；允许仪器仍由提案的仪器集合约束。
- **抢占预览**：抢占申请给出望远镜、仪器和期望区间（`POST /api/preemptions/preview`）。
  系统计算：
  - 被期望区间覆盖的**可抢占**有效预订（普通提案预订一律可抢占；ToO 预订仅在其优先级
    **严格低于**抢占方时可抢占）；
  - 与窗口外前、后相邻有效预订之间**需要的仪器切换时间**和实际可用间隔；
  - 全部冲突原因码：`NON_PREEMPTABLE_RESERVATION`（不可抢占任务）、
    `INSTRUMENT_NOT_SUPPORTED`（望远镜不支持）、`INSTRUMENT_NOT_ALLOWED`（ToO 提案不允许）、
    `QUOTA_INSUFFICIENT`（ToO 配额不足）、`RESPONSE_DEADLINE_PASSED`（超过响应时限）、
    `SWITCH_GAP_INSUFFICIENT`（前/后切换时间不足）。
  预览为只读，不改变任何状态。
- **确认抢占**：`POST /api/preemptions` 携带**抢占业务号**（幂等键）。
  - 可行时在同一事务内一次性：将全部被覆盖的可抢占预订转为**待重排（PENDING_REARRANGE）**并
    向各自提案**归还分钟配额**、扣减 ToO 提案配额并建立新的有效预订、推进望远镜日程版本、
    保存抢占前后日程 JSON 快照、配额变化与受影响预订快照。
  - 存在不可抢占任务、仪器不兼容、配额不足、超过响应时限或切换时间不足中**任意**原因时，
    写入 `REJECTED` 抢占记录（HTTP 422）并返回全部原因，**原日程与配额完全不变**。
  - 相同业务号重放返回原抢占单；业务号相同但申请内容不同返回 409。
- **待重排与重排**：被抢占的普通预订进入 PENDING_REARRANGE，可在**同一提案剩余有效期内**
  （普通提案不限窗口；ToO 提案不晚于其 `validUntil`）寻找新时段
  （`POST /api/reservations/{id}/rearrange`，携带幂等键）。
  - 新时段时长必须与原预订一致，且满足常规时间冲突与仪器切换检查；
  - **重排成功才再次扣减**原提案配额，原预订进入终态 PREEMPTED 并指向新预订；
  - 抢占归还与重排扣减分离，取消、抢占、重排并发不会重复归还或重复消费分钟数。

### 日程版本与查询

- 每台望远镜维护 `scheduleVersion`，每次结构性变更（新建、取消、抢占确认、重排）后递增；
  抢占单保存抢占前/后的版本号。
- `GET /api/preemptions/{businessKey}` 返回抢占状态（CONFIRMED/REJECTED）、前后版本、
  前后完整日程快照、ToO 配额前后分钟数、受影响预订快照列表与冲突原因列表。
- `GET /api/proposals/{code}/pending-rearrangements` 返回提案下所有待重排任务。
- 预订详情额外包含 `status`（ACTIVE/CANCELLED/PENDING_REARRANGE/PREEMPTED）、
  `preemptedByBusinessKey`、`rearrangedFromId`、`rearrangedToId`。

## API 概览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/telescopes` | 登记望远镜（编号、名称、切换分钟数、仪器集合） |
| GET | `/api/telescopes/{code}/schedule` | 查询望远镜日程（仅有效预订，稳定排序） |
| POST | `/api/proposals` | 登记提案（普通提案或含优先级/响应时限的目标机会提案） |
| GET | `/api/proposals/{code}/quota` | 查询提案配额使用情况 |
| GET | `/api/proposals/{code}/pending-rearrangements` | 查询提案的待重排任务 |
| POST | `/api/reservations` | 创建观测预订（携带幂等键） |
| GET | `/api/reservations/{id}` | 查询预订详情（含抢占/重排关联） |
| POST | `/api/reservations/{id}/cancel` | 取消未开始的预订并归还配额 |
| POST | `/api/reservations/{id}/rearrange` | 重排待重排预订（携带幂等键，成功才扣减配额） |
| POST | `/api/preemptions/preview` | 抢占预览：受影响预订、前后切换时间、冲突原因 |
| POST | `/api/preemptions` | 确认抢占（业务号幂等；可行 201，冲突 422 且日程不变） |
| GET | `/api/preemptions/{businessKey}` | 查询抢占单：前后日程/版本、配额变化、受影响预订、原因 |

错误以 RFC 9457 `ProblemDetail` 返回：404 资源不存在，409 幂等/时间/取消冲突，422 业务规则校验失败
（包括被拒绝的抢占，响应体同时是完整的抢占单表示）。

## 开发环境

- JDK 21
- Spring Boot 4.1.1
- Maven Wrapper 3.9.9
- H2

## 本地运行

启动服务：

    ./mvnw spring-boot:run

运行测试：

    ./mvnw clean test
