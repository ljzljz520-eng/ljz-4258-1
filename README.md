# 原奶收货全栈平台

React PWA 用于分仓采样、封签交接、卸载登记、异常反查和放行签署；FastAPI 提供鉴权、设备回放、事件同步、实验室修正和谱系 API；PostgreSQL 保存车辆、牧场批、封签、样品、接收罐及物料边。

## 核心边界

- 系统只记录温度、称重和人员确认的卸载结论，**不控制阀门或任何现场执行机构**。
- 事件采用客户端生成的全局 `event_id`。两台离线终端重放同一卸载时返回 `duplicate=true`，不会重复扣减。
- 数量超额、来源过期、称重偏差、封签不匹配等不覆盖当前状态；事件写入 `event_log` 并标记 `QUARANTINED`，在待协调队列中处理。
- 物料节点和边是 append-only。部分卸载会为仓室创建剩余节点，并把卸载量连到新的混合罐节点；旧罐节点以“结转边”进入新节点。
- 递归 CTE 拒绝成环边，触发器拒绝任何来源节点出量超过其数量。
- 实验室提交新版本结论会提升所有受影响**物理接收罐**的 `lineage_version`，并把未完成放行申请置为 `INVALIDATED`。签署时锁定物理罐并核对节点 ID + 版本，同时检查拒收样品祖先。
- 若签署后发生新卸载，旧签署快照不会被当作覆盖；该旧申请会因节点版本变化而失效，主管必须按新谱系重新申请。

## 数据模型概览

- `farm_batches`：牧场批声明。
- `vehicles` / `compartments` / `seals`：车辆、分仓和封签。
- `material_nodes`：`FARM_BATCH`、`COMPARTMENT`、`TANK`、`SAMPLE` 节点。
- `material_edges`：带数量的有向非循环物料边；混合通过多入边表达。
- `event_log`：LOAD / REFILL / UNLOAD / SAMPLE 事件及协调状态。
- `device_readings`：可回放温度/称重适配器数据，按稳定 `reading_id` 幂等。
- `samples` / `lab_results`：样品与多版本实验室结论。
- `receiving_tanks`：物理罐当前节点指针和谱系版本。
- `release_applications`：放行申请、失效原因和人工签署。

## 本地运行

```bash
docker compose up -d db
python3 -m venv .venv && source .venv/bin/activate
pip install -r backend/requirements.txt
export DATABASE_URL='postgresql+psycopg://milk:milk@localhost:5432/milk_receipt'
cd backend && alembic upgrade head && uvicorn app.main:app --reload
```

前端：

```bash
cd frontend
npm install
npm run dev
```

种子账号：

| 角色 | 账号 | 密码 |
|---|---|---|
| 操作员 | `operator` | `operator123` |
| 实验室 | `lab` | `lab123` |
| 主管 | `supervisor` | `supervisor123` |

种子数据：车辆 `V-101`、仓室 `C1/C2`、封签 `SEAL-C1-A/SEAL-C2-A`、牧场批 `FB-ALPHA..FB-DELTA`、接收罐 `T-A/T-B`。

## 同步 API

`POST /api/sync` 接收一批事件：

```json
{
  "events": [{
    "event_id": "终端生成的稳定UUID",
    "terminal_id": "terminal-7",
    "type": "UNLOAD",
    "client_created_at": "2026-09-30T08:00:00Z",
    "payload": {
      "tank_code": "T-A",
      "lines": [{
        "compartment_id": "66666666-6666-6666-6666-666666666661",
        "source_node_id": "操作员快照中的当前仓室节点",
        "quantity": 300,
        "expected_seal_number": "SEAL-C1-A",
        "weight_reading_id": "scale-replay-001"
      }]
    }
  }]
}
```

响应逐项给出 `ACCEPTED`、`QUARANTINED` 或 `duplicate`。一个事件失败不会抹掉同批已成功事件；失败事件继续保留在终端本地队列。

中途补装 payload 使用 `REFILL`，要求旧封签、新封签和牧场批数量分配；采样使用 `SAMPLE`，可从仓室节点或先卸后的最新罐节点采样。

## 离线可用性与全局物料守恒的取舍

1. **离线优先保证现场可记录**：PWA 的 app shell 可离线打开，表单和事件持久化到本地；网络恢复后再同步。代价是终端短时间不知道其他终端的最新状态。
2. **不能在离线时宣称全局守恒成立**：本地只做快照校验（源节点、数量、封签号、表单约束），真正的库存求和、递归防环、封签和混合罐锁由 PostgreSQL 在同步时完成。
3. **身份幂等替代最后写入获胜**：稳定事件 ID 使重复登记可安全重放；旧节点快照与当前指针冲突时进入协调，而不是用迟到写入覆盖。
4. **人员确认优先于自动化**：温度/称重只是证据和可选核对，异常进入待协调；接收、拒收和放行均由有权限人员确认，系统不控制阀门。

## 异常反查

`GET /api/samples/{sample_id}/impact` 递归遍历祖先和后代：

- 返回所有当前受影响物理接收罐、当前节点、谱系版本和受影响数量；
- 返回尚未卸完的仓室剩余节点和受影响数量；
- 若样品指定 `farm_batch_id`，按递归比例只报告该牧场批贡献；不指定时按样品源节点整体追溯。

## 端到端测试

```bash
./scripts/run_e2e.sh
```

或在已有 PostgreSQL 上：

```bash
createdb milk_receipt_test
cd backend
DATABASE_URL='postgresql+psycopg://milk:milk@localhost:5432/milk_receipt_test' pytest -q
```

覆盖：

1. 中途补装；
2. 先卸后取样；
3. 部分同步失败、重复事件、超额待协调；
4. 并发卸载与放行竞争；
5. 实验室迟到修正使旧放行失效；
6. 混合、部分卸载和全局物料守恒不变量；
7. 设备适配器回放幂等。

## 设备适配器边界

`backend/app/adapters.py` 定义可回放适配器：温度适配器和称重适配器只把历史文件/消息流规范化为 `DeviceReadingIn`，由 `/api/device-readings` 幂等入库；接口和实现均不提供阀门控制。称重可在卸载事件中引用 `reading_id` 做容差校验，温度作为证据供人工判断。
