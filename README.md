# 眠迹 SleepTrace

Android 手機睡眠推估，包名 `com.rsps1008.sleeptrace`。Google Sleep API 與床上加速度計提供睡眠候選，由 App 自動選擇最佳推估並同步，不需要逐筆確認。不是醫療或經過驗證的睡眠分期工具。

App 圖示使用深靛藍夜色、淡紫月牙與藍綠睡眠軌跡，提供 Android adaptive、圓形、一般及 monochrome themed icon；原始生成圖與預覽保存在 `artwork/`。

## 自動記錄睡眠

1. 首次開啟先設定偵測時段；每次開啟 App 都會自動檢查活動辨識、通知、Health Connect 與使用情況存取。可由 App 發起的權限會直接交由系統要求，使用情況存取則開啟 Android 系統設定頁。
2. 偵測時段的開始／結束時間使用 24 小時制拉選欄位，不使用時鐘式選擇器。
3. 完成設定及必要授權後，App 在偵測時段內先等待 Google Sleep API 的高信心睡眠分類；達到工程門檻後才以 1 Hz 啟動加速度計。手機放床上或床邊均由 App 自行評估，不用選擇位置，也不用另外開啟動作偵測。
4. 前景通知只顯示自動睡眠記錄，App 或通知可暫停整體記錄。一般程序回收、開機或 App 更新後會嘗試恢復；若 Android 強制停止或 OEM 限制背景啟動，回到 App 時會自動補啟動。明確暫停後則保留暫停，直到按恢復。
5. 首頁只呈現睡眠紀錄、排程與必要連線狀態，不顯示感測器設定或動作時間軸；並顯示本機已保存的最後一筆 Sleep API 睡眠信心與回報時間，不會為此發起即時查詢，且該分數不是經過校準的準確率。睡眠紀錄的工程分數／理由可在自選詳情查看。
6. 完成 Health Connect 系統授權後，App 自動同步睡眠紀錄。動作推估會在偵測時段結束後產生，不需要開啟 App 或按確認上傳；可選擇修正時間，儲存後也會自動更新。動作資料不會上傳為深眠、淺眠或 REM。

### 電力與硬體策略

首次完成時段與授權引導後，App 會檢查 Android 電池限制。未排除最佳化時會開啟系統允許背景執行的請求；若已被明確限制，則開啟 App 設定，請在電池選項選擇「不受限制／無限制」。返回 App 後重新讀取實際狀態。取消或未調整仍會繼續記錄，不會下次開啟又自動跳轉，首頁保留「允許整晚背景記錄」入口。

小米／Redmi／POCO 僅在首次流程一次性引導「自啟動／背景自啟動」設定。請允許眠迹，並將 App 電池策略設為「無限制」。部分 MIUI／HyperOS 不支援直達頁面時，App 會退回應用程式／一般設定，可搜尋「自啟動」；首頁不常駐顯示小米自啟動區塊。

以上設定需由使用者在系統介面操作，App 不會自行修改。解除限制不會提高取樣頻率或新增持續喚醒，仍採用下列省電策略；也不保證能避開全部廠商背景限制或強制停止。

| 情況 | 要求取樣頻率 | 要求硬體批次回報 |
| --- | --- | --- |
| 時段內，Google 尚未判斷入睡 | 不取樣 | 保留 Sleep API 訂閱及前景服務 |
| Google 睡眠信心值 ≥ 80、有 FIFO | 1 Hz | 使用硬體宣告 FIFO 容量的 80% |
| Google 睡眠信心值 ≥ 80、無 FIFO | 1 Hz | 不支援批次 |
| 未接電且電量 ≤ 15% | 暫停 | 接電或電量恢復後重新評估 |
| 偵測時段外 | 不取樣 | 保留服務通知與時段邊界排程 |

睡眠分類是 Google Play services 定期提供的推估，不是即時或確定的入睡事件；官方舉例可能約每 10 分鐘回報。原始分類與區段以 SQLite 交易、時間索引及 14 天保留期保存，不再為每筆分類重寫完整 JSON。Sleep API 區段會先裁切到每日偵測時段；手機使用只會在已有 Health Connect 寫入權限、準備上傳待同步候選時合併查詢一次，結果會保存，因此週期分析及同步重試不會重複掃描 UsageStats 或重算扣除時間。App 只接受目前時段內、最近 20 分鐘且信心值至少 80 的分類，觸發後持續取樣到時段結束。這個 80 分門檻是未校準的工程規則，可能延後啟動或整晚未觸發；缺少的前段動作資料不會補成安靜，最終仍可使用 Sleep API 區段及手機使用紀錄推估。

依感測器最小取樣間隔調整實際請求；批次延遲以 FIFO 容量 × 取樣間隔 × 80% 換算成 Android API 要求的微秒值，不另設 App 時間上限。若換算結果超過 API `Int` 可表示範圍，才限制為 `Int.MAX_VALUE`。以上是要求值，Android／硬體可能提前回報或以不同頻率取樣。優先使用帶 FIFO 的 wake-up accelerometer；非 wake-up 或無 FIFO 的感測器在 CPU 休眠時可能漏資料，內部保留診斷狀態，不要求使用者處理。接電時也維持 1 Hz，不會提高取樣頻率。

不持有持續 CPU wake lock，不開陀螺儀、麥克風、定位或相機。只用非精準、允許休眠期間執行的時段邊界鬧鐘，因此開始時間可能受系統省電影響而延後；事件本身也會檢查時段。批次以 SensorEvent 的單調時鐘時間轉換成資料時間，不使用整批送達時刻。

每分鐘累積三軸變化的 RMS、活動持續時間、有效覆蓋時間及樣本數。每約 5 分鐘以 SQLite 交易保存摘要；停止、暫停或切換模式時會先要求 flush，最多等待 2 秒，再保存已收到資料。系統直接殺死程序可能遺失最後約 5 分鐘尚未儲存的摘要及未送達批次，這些缺口不補成安靜。每次寫入清理超過 14 天的動作摘要，不保存原始波形，並排除系統備份。

### 試驗規則

- 每分鐘有效覆蓋至少 45 秒才判讀；長間隔、倒序、重複或非有限值樣本不增加覆蓋。
- 相鄰樣本三軸差值 ≥ 0.15 m/s² 算活動；活動時間比例 ≥ 5% 或變化 RMS ≥ 0.20 m/s²，標示該分鐘有動作。這些是可調的工程起點，未以 PSG 校準。
- 床上模式連續安靜 20 分鐘後回推該段起點；完整區段至少 30 分鐘，才成為備援候選。手機使用、缺失／低覆蓋資料或持續活動 5 分鐘會切斷區段；短暫翻動不等於清醒。每個時段只取最長候選，故可能少算分段睡眠。
- 若床上動作有效資料至少 30 分鐘且涵蓋候選一半以上，而其中活動分鐘占比 ≥ 30%，Sleep API 候選分數降低 30 分。重疊來源依調整後的參考分數自動選擇，平手優先 Sleep API；低分不阻擋同步。
- 舊版需要人工處理的紀錄會自動轉入同步佇列。完全沒有候選、有效睡眠不足 30 分鐘或舊紀錄缺少已扣除手機使用的時間明細，App 會自動略過，不要求人工裁決。尚未授予使用情況存取權時，App 使用其餘資料推估並保留限制說明。
- 手機使用區段在本機扣除，並以 Health Connect 的 AWAKE 階段寫入；其他部分為 SLEEPING，不產生深眠／淺眠／REM。
- 保留紀錄 ID 與歷史。睡眠起訖／清醒時間變動時增加 `clientRecordVersion` 並更新同一筆；內容相同不重傳，手動修正不被自動推估覆蓋。同步進行中若資料改版，舊請求不能覆寫新版。
- 同步暫時失敗時由 WorkManager 自動重試，採 10 分鐘起的指數退避；系統可能延後背景執行。程序中斷後會以相同 ID／版本恢復。缺少 Health Connect 授權時保留紀錄，授權後或下次 App 開啟、定期工作時自動繼續。Android 的系統授權不能由 App 自行同意。
- 背景整理預設只重新分析最近 48 小時資料；未完成同步的既有紀錄仍會納入。多個 Sleep API 區段的手機使用資料會在同一輪合併查詢，避免逐段重複掃描 UsageStats。
- 手機位置自動評估：在前後各最多 30 分鐘的連續有效資料中，至少 20 分鐘覆蓋且有至少 3 個短動作分鐘、首末相隔至少 8 分鐘，提供床上證據。至少 25 分鐘幾乎完全靜止則視為床邊傾向，其餘為未知。手機使用前後 2 分鐘、資料缺口與劇烈動作會切斷證據。
- 自動判斷只是未校準的工程規則，無法保證物理位置正確；床墊、床伴、震動通知等都會影響。床邊／未知時改用 Sleep API 與使用紀錄，不把整晚靜止直接算成整晚睡眠。

### 頂部文字與系統列

首頁使用單一自訂標題，依系統狀態列、瀏海與導覽列 insets 安排安全間距。刷新時保留捲動位置，避免因非同步重建內容而把標題捲走。沒有用固定像素高度繞過 edge-to-edge。

## 驗證與耗電量測

使用 Java 21：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.11'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --no-configuration-cache
```

`MotionEngineTest` 涵蓋 Google 分類觸發門檻、1 Hz／FIFO 設定、批次時間、資料缺口、手機使用、床邊放置、跨午夜、候選分段、動作衝突及同步 ID 保留。`MotionRuntimeTest` **僅供可丟棄的模擬器**：會改測試 App 時段、授權並模擬分類與電池狀態，檢查等待分類、觸發取樣、摘要保存、低電量暫停與供電恢復。不要對日常使用的實機執行該測試。

前次加速度計版本於 2026-09-28 驗證：17 個 JVM 測試通過、Lint 零問題，Debug APK 與測試 APK 建置成功；Pixel_10_Pro 唯讀模擬器的 1 個服務整合測試通過。自動同步另有 `AutomaticSyncTest` 測試舊狀態遷移、低分自動上傳、失敗重試、程序中斷恢復、改版競態與清醒階段切分；以替身寫入端驗證，實際 Health Connect 寫入仍需裝置授權測試。

先前自動同步版本：28 個 JVM 測試通過；唯讀模擬器的 `AutoSyncStorageTest` 通過，驗證實際 SharedPreferences 舊資料遷移、失敗後重試、重新讀取、版本及清醒區段保存。主畫面與 Health Connect 資料使用說明頁已在模擬器成功啟動。測試沒有寫入使用者的健康紀錄。

本次自動放置與首頁簡化版本（2026-09-28）：35 個 JVM 測試通過、Lint 零問題，Debug APK 與測試 APK 建置成功。新增 `AutomaticPlacementTest` 的 7 個案例涵蓋重複動作、靜止、手機使用、缺口、位置變化及舊資料。唯讀模擬器的 `MotionRuntimeTest` 驗證開啟首頁即自動啟動、標題及系統安全間距、移除感測選項、AUTO 摘要保存、低電量暫停與供電恢復；另已目視確認正常首頁截圖。上述是模擬資料／模擬器驗證，並非位置準確度或真實入睡準確度驗證。

Google 分類觸發與 1 Hz 更新（2026-09-29）：36 個 JVM 測試通過、Lint 零問題，Debug APK 與測試 APK 建置成功。新增當前時段、信心門檻及事件新鮮度測試，並以 1 Hz 重跑 MotionEngine 覆蓋與動作案例。當時只連接實機，未執行會更動授權、時段及模擬電量的 `MotionRuntimeTest`；Google 實際分類觸發、整夜耗電、FIFO／Doze 行為及睡眠誤差仍需在可丟棄模擬器與日常實機分別驗證。

實際耗電目前未量測；模擬器無法驗證手機的感測器 FIFO、Doze 完整性或一夜耗電。請在同一支手機、相同環境下交替測試各至少 3 晚：整體記錄暫停、未充電記錄、接電記錄。記錄起訖電量、時長、有效資料覆蓋、缺口、實際使用手機時段與主觀入睡／醒來時間。未充電模式用每小時掉電百分點相減估計新增耗電；接電模式須以系統功耗估計或外部電表比較，不能用電池百分比推算。之前討論的耗電數值不是此 App 實測。

Health Connect 寫入及 Google Fit 端顯示仍需以使用者實際帳號、授權與裝置測試；Health Connect 成功寫入不等於已證明 Google Fit 收到資料。

2026-09-28 背景電池與小米自啟動引導更新：35 個 JVM 測試通過，Lint 無未處理問題，APK 建置成功。模擬器 `BackgroundAccessRuntimeTest` 與 `MotionRuntimeTest` 共 2 個測試通過，驗證拒絕授權仍記錄、引導不重複、返回後重新讀取設定及既有低電量策略。Mi Note 10 自啟動入口已唯讀確認存在，但未更動實機設定，完整 MIUI／HyperOS 操作與整夜恢復仍未驗證。

電池豁免採用 Android 的系統請求，因整夜本機感測為核心功能，在該 Intent 建立函式局部抑制 `BatteryLife` lint 並註明理由；不代表已通過 Google Play 審核。參考 [Android Doze 豁免說明](https://developer.android.com/training/monitoring-device-state/doze-standby#support_for_other_use_cases)；小米私有入口參考 [AutoStarter 原始碼](https://github.com/judemanutd/AutoStarter/blob/master/autostarter/src/main/java/com/judemanutd/autostarter/AutoStartPermissionHelper.kt)，未引入額外第三方套件。

Android 依據：[感測器註冊／批次參數](https://developer.android.com/reference/android/hardware/SensorManager)、[FIFO 與 wake-up 感測器](https://developer.android.com/reference/android/hardware/Sensor)、[health 前景服務權限](https://developer.android.com/develop/background-work/services/fgs/service-types#health)。

Health Connect 去重／更新依據：[Client Record ID 與版本](https://developer.android.com/health-and-fitness/health-connect/write-data)。同一 ID 只有較高版本覆寫資料；版本由本機儲存庫遞增，同版重試不產生第二筆。

依 [Health Connect 設定要求](https://developer.android.com/health-and-fitness/health-connect/get-started) 提供健康資料使用說明入口，以及 Android 13 以下的套件可見性宣告。這是系統授權畫面的說明頁，不增加任何逐筆睡眠確認步驟。
