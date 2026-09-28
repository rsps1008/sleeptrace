# 眠迹 SleepTrace

Android 手機睡眠推估，包名 `com.rsps1008.sleeptrace`。Google Sleep API 與床上加速度計提供睡眠候選，由 App 自動選擇最佳推估並同步，不需要逐筆確認。不是醫療或經過驗證的睡眠分期工具。

## 加速度計＋批次處理

1. 首次開啟先設定偵測時段，允許活動辨識與通知，授予「使用情況存取」以排除手機使用。
2. 在「加速度計＋批次處理（試驗版）」選擇手機放置位置，再按「開啟動作偵測」。放在床邊時只記錄手機動作，不據此產生睡眠。
3. 前景服務會保留通知，在設定時段內取樣；App 或通知均可停止。重開機、強制停止、系統終止後，需開啟 App 按「重新啟動」。沒有偷偷從背景重新啟動服務。
4. 可按「更新動作紀錄與睡眠試算」查看最近 24 小時摘要。藍色是安靜、橙色是動作、灰色是資料不足或床邊模式、紅色是系統使用紀錄。安靜分鐘數不是睡眠時數；分數不是經過校準的準確率。
5. 完成 Health Connect 系統授權後，App 自動同步睡眠紀錄。動作推估會在偵測時段結束後產生，不需要開啟 App 或按確認上傳；可選擇修正時間，儲存後也會自動更新。動作資料不會上傳為深眠、淺眠或 REM。

### 電力與硬體策略

| 情況 | 要求取樣頻率 | 要求硬體批次回報 |
| --- | --- | --- |
| 有 FIFO，未接電源 | 5 Hz | 最長 60 秒 |
| 有 FIFO，接上電源 | 10 Hz | 最長 60 秒 |
| 無 FIFO | 1 Hz | 不支援，降頻回報 |
| 未接電且電量 ≤ 15% | 暫停 | 接電或電量恢復後重新評估 |
| 偵測時段外 | 不取樣 | 保留服務通知與時段邊界排程 |

依感測器最小取樣間隔調整實際請求；批次延遲再限制為 FIFO 容量 × 取樣間隔 × 80%。以上是要求值，Android／硬體可能提前回報或以不同頻率取樣。優先使用帶 FIFO 的 wake-up accelerometer；非 wake-up 或無 FIFO 的感測器在 CPU 休眠時可能漏資料，畫面會提示。

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
- 手機位置需要使用者選擇：系統無法可靠分辨放在床上但離人太遠、床邊或人已離床。床墊、床伴、震動通知都會影響結果。

## 驗證與耗電量測

使用 Java 21：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.11'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --no-configuration-cache
```

`MotionEngineTest` 涵蓋功耗設定、FIFO 容量、批次時間、資料缺口、手機使用、床邊放置、跨午夜、候選分段、動作衝突及同步 ID 保留。`MotionRuntimeTest` **僅供可丟棄的模擬器**：會改測試 App 時段、授權並模擬電池狀態，檢查服務啟停、摘要保存、低電量暫停與供電恢復。不要對日常使用的實機執行該測試。

前次加速度計版本於 2026-09-28 驗證：17 個 JVM 測試通過、Lint 零問題，Debug APK 與測試 APK 建置成功；Pixel_10_Pro 唯讀模擬器的 1 個服務整合測試通過。自動同步另有 `AutomaticSyncTest` 測試舊狀態遷移、低分自動上傳、失敗重試、程序中斷恢復、改版競態與清醒階段切分；以替身寫入端驗證，實際 Health Connect 寫入仍需裝置授權測試。

本次自動同步版本：28 個 JVM 測試通過；唯讀模擬器的 `AutoSyncStorageTest` 通過，驗證實際 SharedPreferences 舊資料遷移、失敗後重試、重新讀取、版本及清醒區段保存。主畫面與 Health Connect 資料使用說明頁已在模擬器成功啟動。測試沒有寫入使用者的健康紀錄。

實際耗電目前未量測；模擬器無法驗證手機的感測器 FIFO、Doze 完整性或一夜耗電。請在同一支手機、相同環境下交替測試各至少 3 晚：動作偵測關閉、未充電開啟、接電開啟。記錄起訖電量、時長、有效資料覆蓋、缺口、實際使用手機時段與主觀入睡／醒來時間。未充電模式用每小時掉電百分點相減估計新增耗電；接電模式須以系統功耗估計或外部電表比較，不能用電池百分比推算。之前討論的耗電數值不是此 App 實測。

Health Connect 寫入及 Google Fit 端顯示仍需以使用者實際帳號、授權與裝置測試；Health Connect 成功寫入不等於已證明 Google Fit 收到資料。

Android 依據：[感測器註冊／批次參數](https://developer.android.com/reference/android/hardware/SensorManager)、[FIFO 與 wake-up 感測器](https://developer.android.com/reference/android/hardware/Sensor)、[health 前景服務權限](https://developer.android.com/develop/background-work/services/fgs/service-types#health)。

Health Connect 去重／更新依據：[Client Record ID 與版本](https://developer.android.com/health-and-fitness/health-connect/write-data)。同一 ID 只有較高版本覆寫資料；版本由本機儲存庫遞增，同版重試不產生第二筆。

依 [Health Connect 設定要求](https://developer.android.com/health-and-fitness/health-connect/get-started) 提供健康資料使用說明入口，以及 Android 13 以下的套件可見性宣告。這是系統授權畫面的說明頁，不增加任何逐筆睡眠確認步驟。
