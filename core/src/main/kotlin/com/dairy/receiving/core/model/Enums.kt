package com.dairy.receiving.core.model

/** 单仓工作流状态。多仓混合卸载时每个仓各自保留状态——异常仓不被吞掉。 */
enum class CompartmentStatus {
    REGISTERED,          // 已登记到厂，未核封签
    SEAL_VERIFIED,       // 封签/牧场批/装车时刻核对通过
    STIRRED,             // 搅拌条件满足
    SAMPLED,             // 代表性个体样已留存（卸前）
    READY_TO_UNLOAD,     // 本仓可由收奶员人工开阀
    UNLOADING,
    UNLOADED,
    HELD                 // 挂起：存在未决阻断项
}

enum class SealSource { NFC, WRITTEN }
enum class UnloadBoundary { SEPARATE, COMPOSITE }
enum class SampleKind { INDIVIDUAL, COMPOSITE }
enum class FindingSeverity { INFO, WARNING, CRITICAL }

/** 系统只给建议，不决定接收，更不驱动阀门。 */
enum class Recommendation { ACCEPT, ACCEPT_WITH_NOTE, HOLD, REJECT }

enum class ScreenAssay { NEGATIVE, POSITIVE, PENDING }
