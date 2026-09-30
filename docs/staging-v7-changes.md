# V7 分期證據修正

V7 是 V6 缺陷修正，不是提高 Deep 或降低 SLEEPING 的調參。AUTO 首次耦合在完整歷史成立時確認，確認時的安靜分鐘不會成為正向動作；45 分鐘期限仍錨定最後一個合格動作。存活支持可由一個新動作續期；失效或反證後必須重新完成完整歷史。

短缺口例外只讀取真實完整 v5 分鐘摘要的累積未覆蓋時間 `60000 - coveredMillis`。最長單次缺口、受影響分鐘數與總未覆蓋時間是不同資訊；摘要不能還原未保存的確切漏樣位置或次數。矛盾、部分桶與缺少必要資訊均不套用例外。正式進入判定與 window blocker 共用同一缺口預算；孤立動作的 Deep 維持診斷沿用狀態機的容忍政策。

回放生成：`./gradlew.bat :app:testDebugUnitTest --tests 'com.rsps1008.sleeptrace.StagingReplayTest' --no-configuration-cache`，再將 `app/build/reports/staging-{replay,transition-matrix,diff-intervals}` 複製為 `docs/staging-v7-*`。一致性檢查使用同一測試命令，但不複製；已提交文件若過期會失敗。V4 是隔離的 `FrozenStageV4`，不共用正式 estimator helper。回放所有矩陣格、列、欄、非對角差異及總跨度均以毫秒守恆驗證。

## V7 診斷一致性修正

本輪只修正診斷與正式判定的來源共用，不調整正式分期門檻，也不升版。`SleepStageEstimator.evaluateFormalDecision()` 是每分鐘唯一的正式決策入口；狀態機與 `MinuteDiagnostic` 共用同一快照，快照包含 `priorState`、`currentEligibility`、`entryDecision`、`maintenanceDecision`、`action`、`transitionReason` 及高活動窗口計數前值／候選值／實際更新值。正式維持會使用與退出相同的 `active 3/5`、密集事件、耦合失效及連續 rolling motion 遲滯，不會用單次高 median 提前退出。

短缺口的 `RECENT_WINDOW_INCOMPLETE`、`WINDOW_CONTAINS_GAP`、`MINOR_GAP_BUDGET_EXCEEDED` 與 `windowBlockingIntervals` 同樣由 entry decision 產生。`windowBlockingIntervals` 採半開時間區間的明確表示：輸出 `start..(end-1)` 的 inclusive `LongRange`，代表 `[start,end)`；已通過預算且只是不在最近五分鐘的缺口只列 `ALLOWED_MINOR_GAP`，不列阻擋區間。缺口分鐘仍是 `SLEEPING`。

每分鐘診斷另外保留當時的 `formalStage`、`wasBackfilled`、`safetyCapAdjusted` 與 `finalStage`。因此後續有限回填、活動橋移除或 safety cap 不會被誤報為當時曾 ENTER，最終 stage 也不會反推 `canMaintainDeep`。這些欄位只在記憶體中保存，沒有逐分鐘資料庫寫入。

本輪 review 補上活動橋移除的 provenance：在 active 3-in-5 確認分鐘，只有原本已是 formal `DEEP` 且實際被改成 `LIGHT`／`SLEEPING` 的前面分鐘標記 `retroactivelyAdjusted=true`，理由固定為 `SUSTAINED_ACTIVITY_REWRITE`，並保存觸發確認分鐘的 `retroactiveAdjustmentSourceMillis`。確認分鐘本身不冒充回寫目標，仍只保留自己的 `Action.EXIT`／`exit_active_3_in_5`；孤立活動不標記。`wasBackfilled`、retroactive activity rewrite、`safetyCapAdjusted` 使用獨立欄位，因此後續 safety cap 不會清掉活動來源或理由。

Hard break 的 `transitionReason` 只由同一個正式 `maintenanceDecision.reasons` 集合按固定優先序派生：`PHONE_IN_USE`、`ONSET_GUARD`、`RECORDING_BOUNDARY`、`LEGACY_FEATURE_LIMITATION`、各 `COUPLING_*`／coupling lost、`MISSING_MOTION`／`INSUFFICIENT_COVERAGE`、`NO_SLEEP_EVIDENCE`，最後才是 `exit_unclassified_hard_break`。`currentEligibilityReasons` 不再放 `ONSET_GUARD`；guard 仍可輸出 LIGHT，entry decision 以 guard 阻擋 Deep entry，若維護決策適用並因此退出，guard 也會出現在 maintenance reasons。

診斷匯出新增 `retroactively_adjusted`、`retroactive_adjustment_reason`、`retroactive_adjustment_source_ms`。header 與 row 共用同一 schema，空值、`false`、`0` 分開輸出，欄位數不一致會直接拒絕。這仍是診斷／provenance 修正，沒有提高 `ALGORITHM_VERSION` 或 motion feature version，也沒有改動正式 stage intervals。

`StagingReplayTest` 預設是 check-only，不覆寫 `docs/`；只有明確設定 `SLEEPTRACE_UPDATE_REPLAY_DOCS=true` 才進入文件更新模式。雜湊對象是原始 classpath resource bytes，沒有先正規化換行、空白或排序。`ALGORITHM_VERSION=7`、`MotionAccumulator.CURRENT_FEATURE_VERSION=5` 保持不變。
