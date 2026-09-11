# 原奶槽车收奶核对系统（收奶区 Android 移动端）

按**独立仓室**核对牧场批、装车时刻、封签、温度、感官与代表性样品；多仓混合卸载时，
每个组分仓的身份、样品链、异常记录**独立保留**，异常仓不会被混合稀释。

系统只做**时序/身份/样品代表性提示与留痕**，**不自动开卸奶阀、不自动决定接收**；
开阀与接收是收奶员/主管的人工动作，系统仅在条件不满足时拒绝登记并要求双签覆核。

## 模块

| 模块 | 类型 | 职责 |
|---|---|---|
| `core` | 纯 Kotlin/JVM | 领域模型、规则引擎（封签/温度/搅拌取样/混卸身份/实验室）、工作流状态机、审计链、报告、设备稳定值算法 |
| `applogic` | 纯 Kotlin/JVM | 平台无关的应用层：离线快照 JSON 编解码、存储端口、NFC 扫签路由、BLE 设备模拟 |
| `app` | Android (Kotlin + Compose + Room) | 收奶区设备 UI、Room 离线库（仓室/封签/样品链）、NFC NDEF 读取、BLE GATT 接入 |

关键设计：

- `core` 无 Android 依赖，规则在 JVM 单测里逐场景验证；Android 只是薄适配层。
- 每车一个 `TripWorkflow`，状态可序列化为 `TripMemento`（`MementoCodec` JSON）。
  Room 同时保存无损快照 JSON（保证恢复后规则重放一致）与关系行（列表/检索/逐仓页面）。
- 封签权威顺序：**NFC 读签 > 手写件**。手写不清只产生留痕警告；无 NFC 且手写不可辨即无法核验（阻断）。
- BLE 温度计/采样秤的“稳定值”由 `StableReadingAccumulator` 统一判定（连续 N 次波动 ≤ ε），
  未稳定值可显示但不进入判定。
- 混卸（`COMPOSITE`）身份保持三条件：组分仓全部有**卸前个体样**、无**未决阻断项**、
  **混合样登记且组分一致**。任一不满足即 `COMPOSITE_TRACEABILITY_LOST`，拒绝混卸声明。
- 缺陷分三级：`WARNING`（附条件接收/留痕）、`CRITICAL+blocking`（挂起，需处置或主管双签）、
  抗生素阳性/掺假（建议拒收）。主管覆核必须填理由，缺陷记录**保留不删**、标记覆核人。

## 五类现场异常的处置（均有端到端测试）

1. **封签号手写不清** — 无 NFC 且不可辨 → `SEAL_UNVERIFIED` 阻断、拒绝登记开阀；
   NFC 与装车单一致仅手填不清 → `SEAL_ILLEGIBLE` 警告留痕，按 NFC 核验。
2. **车载温度记录缺段** — 区间合并算出缺段时长，超阈 `TEMP_LOG_GAP` 警告（不自动拒收）；
   探针与车载记录偏差超限另报；探针超拒收线阻断。
3. **一仓先卸后取样** — 缺卸前个体样时系统拒绝登记开阀；现场物理绕过开阀可事后补录
   （`recordPhysicalBypass`，审计留痕），晚于开阀时刻的个体样判 `SAMPLE_AFTER_UNLOAD` 阻断。
4. **混合样瓶贴错** — 瓶签 NFC 与系统绑定不符 → `SAMPLE_LABEL_MISMATCH` 阻断，
   混卸声明被拒；正确仓与错误仓在报告中各自独立成段，身份不连坐。
5. **中途补装另一牧场原奶** — 罐口签身份与预报不符（或司机自报/GPS 证据）→
   `RELOAD_DETECTED`，该仓立即 `HELD`，手续补全也不能开卸。

另含正向场景：合规混卸后实验室发布某仓个体样抗生素阳性，可精确回溯拒收该仓而不连坐其他组分。

## 构建与测试

需要 JDK 17、Android SDK 34（仅构建 `:app` 时）。

```bash
# 全部业务测试（无需 Android SDK/设备）
gradle :core:test :applogic:test

# 下载 :app 全部传递依赖（不触发 aapt2）
gradle :app:resolveAllArtifacts

# 标准 Android 构建（x86_64 CI / 开发机）
gradle :app:assembleDebug
```

> ARM64 Linux 主机说明：Google 目前只发布 x86_64 的 `aapt2`，无法在原生 ARM64 上执行
> AGP 资源处理。本仓库提供 `scripts/typecheck-app.sh`，用 kotlinc + Compose 编译器插件
> 配合 android.jar 与 AAR classes.jar 对 app 模块做**完整 Kotlin/Compose/Room 类型检查**
> （已验证 154 个类零错误零警告）。APK 打包请在 x86_64 环境（或标准 CI）执行。

## 操作流（移动端）

到厂登记 → 逐仓：罐口 NFC 核身份 → 封签（NFC+手写复核）→ BLE 探针稳定温度 + 关联车载记录
→ 感官 → 搅拌确认 → 规定深度取样（BLE 秤稳定值、瓶签 NFC 绑定）→ 声明卸奶边界（独立/混合）
→ 收奶员**人工开阀**（系统仅在全部硬条件满足时登记）→ 关阀 → 实验室结果按个体样回指仓室
→ 生成按仓分段的接收报告与完整审计链。
