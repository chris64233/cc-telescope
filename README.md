# cc-telescope

管理观测提案、仪器需求和台站时间。

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

## 业务规则

### 资源登记

- **仪器**：按名称唯一登记（`POST /api/instruments`）。
- **望远镜**：编号唯一，登记时声明支持的仪器集合，以及每次切换仪器的
  固定准备时长 `switchOverMinutes`（分钟）。
- **提案**：唯一编号 `proposalNo`、允许使用的仪器集合和总观测分钟配额。

### 预订

- 预订包含提案、望远镜、仪器和起止时间（`POST /api/bookings`），时间为
  UTC 的 ISO-8601 格式，区间采用**左闭右开**语义 `[startAt, endAt)`。
- 预订时长必须是正整数分钟，并从提案剩余配额中扣减。
- 仪器必须**同时**被望远镜支持且被提案允许，否则返回 `409`。
- 同一望远镜上有效（未取消）的预订不得时间重叠；首尾相接不算重叠。
- **仪器切换间隔**：相邻预订使用不同仪器时，两者之间必须至少留出该望远镜
  的 `switchOverMinutes` 分钟准备时长；使用相同仪器时可以首尾相接。
  `switchOverMinutes = 0` 时不同仪器也允许首尾相接。
- 新预订会同时检查其前驱与后继相邻记录，而不仅是直接时间重叠。
- 取消的预订立即释放望远镜时段与提案配额。

### 幂等

- 创建预订可携带幂等键（请求头 `Idempotency-Key` 或请求体 `idempotencyKey`，
  优先使用请求头），幂等键在数据库层面有唯一约束兜底。
- 相同键、相同内容（提案、望远镜、仪器、起止时间）重放时返回原始预订，
  不会重复扣减配额。
- 相同键但内容不同返回 `409 Conflict`。

### 取消

- `POST /api/bookings/{id}/cancel` 仅允许取消**尚未开始**的预订；
  开始后的预订不得取消（`409`）。
- 取消在同一事务内把预订时长归还提案配额；重复取消返回 `409`，
  配额最多归还一次。

### 并发一致性

- 预订事务中按固定顺序（先提案行、后望远镜行）获取悲观写锁，
  避免死锁；配额扣减与排程冲突检测处于同一事务，
  并发预订不会超卖提案配额，也不会产生时间冲突。
- 预订、配额扣减、取消归还共享同一事务边界（JPA + H2）。

### 查询

- `GET /api/telescopes/{code}/schedule`：望远镜日程，仅含有效预订，
  按 `startAt` 升序、`id` 升序稳定排序。
- `GET /api/proposals/{proposalNo}/quota`：提案配额
  （总额 `totalMinutes`、已用 `usedMinutes`、剩余 `remainingMinutes`）。

## API 示例

登记望远镜（30 分钟切换间隔）与提案（120 分钟配额）：

    POST /api/telescopes
    {"code":"T1","name":"一号镜","switchOverMinutes":30,
     "instruments":["CAM","SPEC"]}

    POST /api/proposals
    {"proposalNo":"P-001","totalMinutes":120,"instruments":["CAM"]}

创建预订：

    POST /api/bookings
    Idempotency-Key: booking-0001
    {"proposalNo":"P-001","telescopeCode":"T1","instrument":"CAM",
     "startAt":"2030-01-10T10:00:00Z",
     "endAt":"2030-01-10T11:00:00Z"}
