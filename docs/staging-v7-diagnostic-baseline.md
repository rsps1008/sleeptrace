# V7 診斷一致性修正前基線

基準提交：`0ee7451859569f62d805e81dd580f5b439a41260`（V7，`ALGORITHM_VERSION=7`、感測 `featureVersion=5`）。本文件保存本次 review 在修改前可重現的診斷矛盾；它不是新的分期 golden，也不代表生理真值。

## 修正前失敗案例

| 案例 | 輸入 | 正式狀態機 | 修正前診斷／來源 |
| --- | --- | --- | --- |
| 密集短動作 | 第 40、41 分鐘各 `movementEvents=5`，先前已在 Deep | 第 41 分鐘 `exit_dense_events`，退出 Deep | 舊 `canMaintainDeep()` 只看五分鐘 `validMinutes/activeMinutes/medianRms`，未帶入 dense-event exit；同一分鐘可能仍回 `true` |
| 低訊號差異 | 全晚 BED RMS 狹窄，`baseline.narrowDistribution=true` | `canStage=false`，保持 `SLEEPING` | 舊 `canMaintainDeep()` 沒檢查 `baseline.narrowDistribution`，只要 minute 可用仍可能回 `true` |
| 合法短缺口仍在最近五分鐘 | 真實 `MotionAccumulator` 產生 `coveredMillis=58,000`、`longestGapMillis=2,000` 的第 40 分鐘 | 第 41～44 分鐘因最近五分鐘未完整而不能進入 | 舊診斷的 `windowBlockingReasons` 可能是空集合，只列 `ALLOWED_MINOR_GAP`；真正的 recent-window blocker 沒有被保存 |
| 合法短缺口離開最近五分鐘 | 同一第 40 分鐘缺口，第 45 分鐘其餘條件合格 | 第 45 分鐘可以進入，缺口只是不阻擋資訊 | 舊 `windowBlockingIntervals()` 仍把第 40 分鐘放入阻擋區間，與 `canEnterDeep=true` 矛盾 |
| 同分鐘累積超預算缺漏 | 真實 `MotionAccumulator` 漏第 10、20、30、40 秒，`coveredMillis=52,000`、最長單缺口仍為 2 秒 | 不適用短缺口例外 | 舊診斷只以最長缺口／`WINDOW_CONTAINS_GAP` 粗略表示，沒有明確的累積預算原因 |

上述案例使用正式 `SleepStageEstimator.analyze()` 的時間線；沒有以另一套簡化演算法當作基準。修正後回歸測試位於 `StagingDiagnosticConsistencyTest`，並保留正式 stage 輸出回放的既有文件比對。

## fixture 來源基線

修正前 `StagingReplayTest` 以常數寫入 `fixture_sha256`。該字串當時恰好等於 fixture 的 SHA-256，但測試沒有證明雜湊來自本次實際讀取的 bytes，因此改動來源檔案時可能漏報。修正後由同一次 classpath resource `ByteArray` 讀取計算 SHA-256，再以同一份 bytes 用 UTF-8 解析；`StagingReplayHashTest` 另外驗證相同 bytes 穩定、可解析但不同 bytes 的來源可辨識，且分期總數相同時 hash 仍不同。
