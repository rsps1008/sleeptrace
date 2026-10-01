# 2026-10-01 演算法、省電與穩定性審查：規則 11

起始版本 `272f743`，工作區原先乾淨。本輪檢查取樣、FIFO／timestamp 特徵、動態觀測、AUTO 耦合、Sleep API／motion 候選、分期與原因診斷、UsageStats 快照、規則遷移及自動同步。使用者選定「接近結束時等 10 分鐘，其餘保留 20 分鐘」。

App 仍為 1.0.1／versionCode 2；正式感測要求 10 Hz，feature v7，motion DB 4／sleep DB 11。候選邊界規則有變更，`SleepStageEstimator.ALGORITHM_VERSION` 升為 11，沿用既有近期重算及撤銷流程。

## 動態觀測規則

| 低分串開始時間 | 最少連續低分回報 | 最少跨度 |
| --- | --- | --- |
| 後半窗，但早於原排程結束前一小時 | 3 筆 | 20 分鐘 |
| 原排程結束前一小時內，或已延長的觀測期間 | 2 筆 | 10 分鐘 |

共同條件：confidence ≤ 20；先前有至少早 30 分鐘的 confidence ≥ 80；相鄰回報不超過 15 分鐘；末筆距現在不超過 10 分鐘。低分串起點決定採用哪一列，不因等待到末段就把先前較早的低分串改套快速條件。成功後窗口結束於有效低分串的起點。

修正 `takeLastWhile` 的證據累積問題：舊版整串低分只要包含一個長缺口，或從前半夜開始，即使後面已重新累積完整證據仍無法關閉。新版只取後半窗最近一段連續低分；缺口重設證據，不跨缺口算等待，也不永久阻擋後續證據。

延長維持原規則：名義結束前 20 分鐘起，最新回報仍有近期高分支持才延至回報後 30 分鐘；新支持可續期，但不能超過下一窗開始。`now == effectiveEnd` 可續期、`now > effectiveEnd` 不復活；closed 一旦保存不回溯重開，全天窗口不套用動態結束。UsageStats、候選、服務、分類訂閱及鬧鐘繼續共用同一有效窗口。

## 其他修正與省電措施

| 發現 | 修改 | 驗證方式 |
| --- | --- | --- |
| 每筆感測回呼都解析日期、時區及排程窗口 | `MotionWindowLookup` 快取已解析窗口，設定／延長／關閉／時區更新時失效 | 無途中更新的 8 小時 10 Hz 流，288,000 個事件只做 1 次日曆解析；端點、提前關閉、延長、全天換窗、時區刷新皆有測試 |
| 原窗外 callback 分支位於 `windowAt(...) ?: return` 後，無法要求重估 | 窗外／下一窗事件要求一次非同步控制端更新，避免廣播遲到時一直維持原 listener；不新增定時輪詢 | 快取邊界 JVM 測試、服務既有 sensor／flush 模擬器測試及來源審查；未在 OEM 上驗證遲到鬧鐘情境 |
| 設定查詢可並行，較早的慢查詢可能最後覆蓋新窗口 | 單一 consumer 與 conflated Channel，串行讀取並依序送入 Handler | 受控 barrier 證明 100 個待處理請求合併為一次後續讀取，發布順序維持舊→新；查詢失敗可恢復、取消向外傳 |
| Sleep API 訂閱／取消的非同步回應會污染快取 | `SleepSubscriptionController` 串行送出、合併最新目標；失敗等下次外部觸發 | 暫停→恢復、100 次重複請求、取消、force、晚到 callback 與失敗重試；不宣稱 Google 服務實際投遞已驗證 |
| 同一鬧鐘時間戳無法證明開機／權限變更後鬧鐘仍有效或已改為精準 | 系統恢復、時間／時區、精準鬧鐘權限變更時清除排程快取再安排 | 來源審查與 Android 建置；未做 OEM／Doze／精準鬧鐘授權撤回實驗 |
| 舊 BED 相容摘要可跨 recordingId／featureVersion 拼成安靜候選 | 邊界切段，各段重新累積，不把兩段各 20 分鐘拼成 40 分鐘睡眠 | 修正前可重現；同一版本／錄製的正向控制保留，分段不足者不產生候選，已同步舊列走 RETIRED、未同步走 SKIPPED |
| v7 仍掃描不使用的 legacy P70 佐證點 | 當 baseline 不提供 relative-quiet 門檻時跳過該掃描 | 既有原始事件→AUTO→分期回歸、原因／provenance 測試及凍結回放通過；不改分期門檻 |
| 批次永久失敗後逐筆 fallback，遇到暫態失敗仍繼續大量呼叫 | 暫態失敗後未送列保留同 ID／revision，等待既有退避 | 修正前三筆範例仍呼叫第三筆；修正後停止於第二筆，之後重試僅送未完成列 |

FIFO 批次、SensorEvent timestamp、低電量暫停、接電恢復、睡眠窗限定服務及分鐘摘要保存策略不變。沒有新增感測器、持續 wake lock、提高取樣頻率或每秒輪詢。查詢／呼叫次數減少是工程證據，不等於已量得電池百分比改善。

## 審查與回歸

1. 第一輪新增 observation regression，在未修正基線得到 19 項中 4 項失敗：末段 10 分鐘、延長期間 10 分鐘、缺口後重新累積及前半夜低分污染。修正後政策與 repository 測試通過。
2. 第二輪先重現逐筆同步在暫態失敗後仍繼續呼叫，以及 motion 候選跨錄製／版本拼接，再修正並回歸。補上設定更新順序、快取失效與規則撤銷測試。
3. 第三輪審查非同步訂閱與鬧鐘恢復；補上串行訂閱狀態機及受控完成／失敗測試。最後核對實際差異、完整測試、Lint、APK、裝置整合及文件一致性。

最終在本次已審查的資料流與可執行測試範圍，未發現尚未處理的可確認缺陷。這不是全裝置、全情境無錯誤的保證。

## 驗證

JDK `C:\Program Files\Java\jdk-21.0.11`，Windows／PowerShell：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.11'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest assembleRelease --no-configuration-cache
git diff --check
```

- 262 項 JVM tests，0 failure／error（基線 243 項，本輪新增 19 項）。
- Lint 26 warnings／0 errors，Debug、AndroidTest、R8 unsigned Release APK 建置成功。
- 一次性 Pixel_10_Pro Android 16 模擬器，啟動參數 `-port 5580 -read-only -no-snapshot -no-window -no-audio`；所有 adb 命令明確指定 `-s emulator-5580`。不在實體手機安裝或執行。
- 13 項 instrumentation：`StagingStorageRuntimeTest` 9、`SleepObservationIntegrationTest` 3、`MotionRuntimeTest` 1。新增的 SQLite／SharedPreferences／Main 整合測試驗證兩筆跨 10 分鐘低分保存閉合、通知在 commit 後、遲到高分不能重開。既有服務測試涵蓋等待分類、10 Hz callback、flush、低電量／接電、首頁、CSV 及詳情。
- 裝置輸出存於 `app/build/reports/algorithm-v11-instrumentation.txt`；JVM 與 Lint 報告保留於既有 build 目錄。
- `docs/staging-v11-*` 為 estimator-only 凍結 fixture 報告；與規則 10 的 transition matrix／diff intervals 相同，文字報告只改規則版本。舊真實 1 Hz fixture regression 保持既有要求，不是新 v7 整晚資料。

## 官方資料與證據限制

[Google SleepClassifyEvent 文件](https://developers.google.com/android/reference/com/google/android/gms/location/SleepClassifyEvent) 以約每 10 分鐘作回報週期範例，未提供本 App 的準時關窗保證。因此末段兩筆規則在典型回報節奏下少等一筆；它的 10 分鐘指證據時間跨度，不是從實際醒來起算的保證延遲。10／20 分鐘門檻是使用者選定的工程政策，不是官方認可的生理界線。

[AOSP 感測批次文件](https://source.android.com/docs/core/interaction/sensors/batching) 說明 FIFO 批次可減少主處理器喚醒，批次延遲不應改變事件 timestamp。這支持保留現有 FIFO／事件時間策略並減少每批回呼的重複工作；本文沒有從此推算此手機能省幾成電量。

未驗證實機整夜耗電、OEM／Doze／FIFO 完整性、Google 真實回報延遲、Health Connect 真實寫入／刪除、PSG 或穿戴裝置真值。末段較短等待可能較容易被持續誤分類影響；單次低分、缺資料與靜止仍不關窗。沒有資料支持改動 Deep 生理門檻或宣稱分期準確率提高。
