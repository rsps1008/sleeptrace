# V7 分期證據修正

V7 是 V6 缺陷修正，不是提高 Deep 或降低 SLEEPING 的調參。AUTO 首次耦合在完整歷史成立時確認，確認時的安靜分鐘不會成為正向動作；45 分鐘期限仍錨定最後一個合格動作。存活支持可由一個新動作續期；失效或反證後必須重新完成完整歷史。

短缺口例外只讀取真實完整 v5 分鐘摘要的累積未覆蓋時間 `60000 - coveredMillis`。最長單次缺口、受影響分鐘數與總未覆蓋時間是不同資訊；摘要不能還原未保存的確切漏樣位置或次數。矛盾、部分桶與缺少必要資訊均不套用例外。正式進入判定與 window blocker 共用同一缺口預算；孤立動作的 Deep 維持診斷沿用狀態機的容忍政策。

回放生成：`./gradlew.bat :app:testDebugUnitTest --tests 'com.rsps1008.sleeptrace.StagingReplayTest' --no-configuration-cache`，再將 `app/build/reports/staging-{replay,transition-matrix,diff-intervals}` 複製為 `docs/staging-v7-*`。一致性檢查使用同一測試命令，但不複製；已提交文件若過期會失敗。V4 是隔離的 `FrozenStageV4`，不共用正式 estimator helper。回放所有矩陣格、列、欄、非對角差異及總跨度均以毫秒守恆驗證。
