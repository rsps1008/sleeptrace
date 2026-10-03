# 匯出 CSV 的離線門檻回放

本工具只產生 JVM 測試用的演算法副本，不修改 `app/src/main`、App 版本、正式門檻、手機資料或 Health Connect。正式程式沿用規則 11。

`prepare.py` 以當前工作區原始碼生成實驗副本；每個文字替換都要求唯一匹配，原始碼變動不符合預期時直接停止。`manifest.json` 保存來源、輸入及生成檔 SHA-256。`replay.init.gradle` 只在明確使用 `-I` 時加入實驗測試來源，正常 App／測試建置不包含這些副本。

## 重現本次比較

在專案根目錄使用 JDK 21。Python 使用 Codex `load_workspace_dependencies` 回傳路徑；以下路徑是本次環境。

```powershell
$replayPython = 'C:\Users\CHINTING\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe'
& $replayPython tools/offline-replay/prepare.py 'E:\Windows\Downloads\sleeptrace_motion_2026-10-02.csv'
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.11'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat -I tools/offline-replay/replay.init.gradle :app:testDebugUnitTest --tests com.rsps1008.sleeptrace.OfflineThresholdReplayTest --tests com.rsps1008.sleeptrace.AutomaticPlacementTest --tests com.rsps1008.sleeptrace.SleepStageEstimatorTest --tests com.rsps1008.sleeptrace.TenHertzReviewRegressionTest --tests com.rsps1008.sleeptrace.SparseCouplingNightReplayTest --no-configuration-cache
```

繪圖需要 matplotlib。僅在本機忽略的建置目錄安裝依賴，不更動 bundled runtime：

```powershell
& $replayPython -m pip install --target app/build/offline-replay/python-deps matplotlib==3.11.2
& $replayPython tools/offline-replay/summarize.py 'E:\Windows\Downloads\1035824_0.jpg'
```

繪圖腳本的截圖 ROI、日期及中文 Windows 字型是本次分析的固定設定。更換小米截圖時必須重新核對尺寸、睡眠時長、軌道範圍及掃描列，不能直接套用本次像素值。若測試未成功，不能執行摘要步驟並把舊輸出當成新結果。

### 第二輪：兩組偏好設定的鄰近比較

沿用上面的 Python、JDK 21 與 matplotlib 環境。三個步驟都必須指定第二輪，生成檔與結果才會讀寫相同位置：

```powershell
& $replayPython tools/offline-replay/prepare.py 'E:\Windows\Downloads\sleeptrace_motion_2026-10-02.csv' --round 2
.\gradlew.bat -DofflineReplayRound=2 -I tools/offline-replay/replay.init.gradle :app:testDebugUnitTest --tests com.rsps1008.sleeptrace.OfflineThresholdReplayTest --tests com.rsps1008.sleeptrace.AutomaticPlacementTest --tests com.rsps1008.sleeptrace.SleepStageEstimatorTest --tests com.rsps1008.sleeptrace.TenHertzReviewRegressionTest --tests com.rsps1008.sleeptrace.SparseCouplingNightReplayTest --no-configuration-cache
& $replayPython tools/offline-replay/summarize.py 'E:\Windows\Downloads\1035824_0.jpg' --round 2
```

第二輪在 `app/build/offline-replay/round2/` 保存中介資料；報告輸出至 `docs/offline-replay-2026-10-02-round2/`，不覆寫第一輪。測試會用第一輪報告 `intervals.csv` 核對 A／B 的完整 LIGHT／DEEP 起訖，所以重現第二輪需保留第一輪報告。

## 回放範圍

- 輸入限定單一 session、feature v7 的本次 CSV；以其 `exact_computed_parts` 還原固定起訖及已接受睡眠證據。沒有重跑候選選擇、Sleep API 回報時序、觀測窗、UsageStats 查詢、儲存或同步。
- 原始分類樣本和完整使用 snapshot 不在 CSV。只使用 session 結束前的感測分鐘，session 內無已知清醒；以覆蓋全 session 的 SleepSegment 還原已接受證據，全天 schedule 僅作覆蓋固定範圍的離線容器。必須先通過正式程式與 CSV 的逐分鐘等價檢查，不能推廣成通用匯入器。
- 取樣摘要保持原值，RMS 等小數只有 CSV 的六位精度；不合成原始三軸訊號、不回補 01:27 前資料、不調整感測頻率或缺口容忍。
- 基準需通過完整分段、每分鐘 `canStage`、正式 action、耦合 state／age 的一致性；生成副本另需與正式程式分段、action、primary reason 一致。
- 每組檢查時間守恆、不可在無分期資格時輸出 Deep、最短 Deep 段限制。反例使用同一夜摘要變形：完全靜止、全段手機使用、每分鐘更換 recordingId、各分鐘 3 秒缺口，皆不得取得相應 Deep／床面支持。
- 這些控制驗證程式限制，不衡量真實誤判率。只有一晚、沒有生理真值，不能據此選出已校準的正式門檻。

## 實驗維度

| CSV 欄位 | 意義／基準 |
| --- | --- |
| `movements` | FAST／SPARSE 建立支持需要的合格動作分鐘數，3；原有時間跨度和有效歷史長度不變 |
| `noise` | 建立／一般續期之動作 RMS 相對底噪倍數，3.0；絕對下限 0.015 不變 |
| `renew_noise` | 已建立支持後另行放寬的續期倍數；-1 表示未啟用 |
| `hold_min` | 最後合格動作後支持期限，45 分鐘 |
| `peak_reset` | 僅最大動作差值的 handling 門檻，1.5 m/s²；平均 RMS／姿態的 1.5 門檻不變 |
| `composite_peak` | 短峰值額外要求 active ≥8 秒或 posture ≥0.5 才重設；基準 false，純實驗條件 |
| `entry_window` | 深睡入口連續窗口，15 分鐘；其他品質要求及至少 10 個 BED 分鐘保留 |
| `entry_percentile` | 嚴格入口之窗口 RMS 中位數上限，當晚 P50；近期 5 分鐘 P70 條件不變 |
| `min_bout` | v7 Deep 短段過濾，10 分鐘；短於門檻的段落只刪除、不延長 |
| `exit_percentile` | 近期 5 分鐘 RMS 中位數超過當晚 P70，累計高動作窗口 |
| `exit_windows` | 上述連續超標窗口數，3；每分鐘更新，窗口重疊，並非三個獨立 5 分鐘 |
| `exit_events` | 最近 5 分鐘動作事件數達 8 時退出 |
| `exit_rms_lookback` | 第二輪獨立比較退出 RMS 的 3／5／7 分鐘窗口；活動分鐘、動作事件數仍使用原本 5 分鐘 |

第一輪內含單因子、有限組合，以及四種支持策略的短段／退出比較，共 383 次回放、362 組不同設定，重複組合作為對照。第二輪以 A／B 為起點，增加組合及 C 鄰近設定，共 1,268 組不同門檻；重複參數組先去除。入口／退出任意百分位均以正式 `percentileSorted` 對完整基準樣本線性插值，不把 P60／P62／P67 近似成其他欄位。沒有輸入小米分段、目標 Deep 時數、固定睡眠週期或目標段數；小米只由後處理讀圖比較。

輸出：`app/build/offline-replay/` 為原始中介資料與生成副本；`docs/offline-replay-2026-10-02/` 為去除 session ID 的比較資料、圖表、來源雜湊與驗證摘要。
