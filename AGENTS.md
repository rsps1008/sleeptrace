# 眠迹 SleepTrace：專案接手指南

本文件供後續對話、AI 與開發者接手使用，適用於本專案全目錄。內容依 2026-09-28 的程式與已完成驗證整理；後續修改功能時，請同步維護本文件及 `README.md`。若描述與程式不同，先查實作並說明差異，不要把規劃或舊對話當作已完成功能。使用者後續明確指示優先於本文件。

## 1. 專案目標與已確定的使用者需求

- Android 手機睡眠推估 App，名稱 **眠迹 SleepTrace**，applicationId／namespace 為 `com.rsps1008.sleeptrace`，Gradle 專案名稱為 `SleepTrace`。工作目錄目前為 `E:\Git\sleep`，保留現有名稱與包名，除非使用者要求更名。
- App 圖示為深靛藍夜色底、淡紫月牙與藍綠睡眠軌跡；adaptive icon 使用 `ic_launcher_background`、`ic_launcher_art` 與 `ic_launcher_monochrome`，各密度另有一般及圓形 legacy WebP。原始生成圖與預覽保存在 `artwork/`。
- 優先省電；接受不非常精準的推估，但要以實際可取得的資料判斷。手機通常放在床上，也必須處理床邊放置情況。
- **放置位置與動作偵測由 App 自動處理，不要要求使用者選床上／床邊或另外開啟感測器。首頁以睡眠時間與記錄狀態為主，避免顯示感測器參數／診斷圖表。**
- 已知的手機使用時間不可算成睡眠。沒有使用情況存取權時，程式仍使用其餘資料自動推估，並顯示無法排除手機使用的限制；不可宣稱此時已完整排除。
- **所有有效睡眠候選由 App 自行選擇最佳推估並自動同步，不要恢復「待確認」、逐筆確認上傳或低分需使用者裁決的流程。**
- 低參考分數不阻擋上傳；不足以形成有效睡眠紀錄的資料由 App 自動略過。系統權限仍由使用者授予，不能由 App 代為同意。
- 可保留「修正時間」作為自選操作，但不能把它變成必要步驟。修正儲存後也自動同步。
- 使用者最初希望睡眠記錄到 Google Fit；**現有程式寫入 Health Connect，沒有直接呼叫 Google Fit REST／舊版 Fit SDK**。Health Connect 成功不等於 Google Fit 已讀取或顯示成功。
- 深眠、淺眠、REM 是非必要功能，目前尚未實作。動作少不等於深眠，安靜／活動摘要不應冒稱有效睡眠分期；未來若增加，須另行說明方法、限制與耗電驗證。

## 2. 技術與環境

| 項目 | 目前設定 |
| --- | --- |
| 主機／Shell | Windows／PowerShell；搜尋優先使用 `rg`、`rg --files` |
| Java | Java 21：`C:\Program Files\Java\jdk-21.0.11` |
| Android SDK | 本機為 `C:\Users\CHINTING\AppData\Local\Android\Sdk`；其他環境以 `local.properties` 為準 |
| 建置 | Gradle Wrapper 9.6.0、Android Gradle Plugin 9.4.1 |
| Android | minSdk 29、compileSdk 37、targetSdk 37；版本目前為 1.0／versionCode 1 |
| 語言／UI | Kotlin、AppCompat／Material；`MainActivity` 使用程式建立 View，沒有 Compose |
| 背景工作 | Coroutines、WorkManager、health 類型前景服務 |
| 資料保存 | Preferences DataStore、SharedPreferences JSON、SQLiteOpenHelper；沒有 Room |
| 健康／活動 SDK | Health Connect 1.1.0、play-services-location 21.4.0 |

版本依據為 `app/build.gradle.kts`、`gradle/libs.versions.toml`、`gradle/wrapper/gradle-wrapper.properties`。目前只套用 `com.android.application` 外掛並使用 AGP 內建 Kotlin 支援，不要因一般舊版範本而補上重複 Kotlin Android 外掛或 Java 25 toolchain。

這是 Android Gradle 專案，不使用 Maven。2026-09-28 根目錄未見 `.git`；後續 Git 操作前先查當時狀態，不要假設已存在分支、遠端或提交。

## 3. 功能與判斷規則

### 偵測時段及 Sleep API

- 首次開啟要求設定每日偵測時段，設定前 `configured()` 為 false，不啟用睡眠分析。時段支援跨午夜；起訖相同時目前視為 24 小時。畫面以 24 小時制拉選欄位選取時間，不使用時鐘式選擇器。
- `MainActivity` 每次啟動會自動檢查活動辨識、通知、Health Connect 與使用情況存取；可由 App 發起的權限會直接啟動系統授權流程，使用情況存取則帶到 Android 系統設定頁。畫面保留狀態與重新檢查入口，不要求使用者逐項尋找設定按鈕。
- 完成上述流程及時段設定後，若未暫停，會一次性引導背景電池設定，再引導小米自啟動。拒絕／返回也繼續記錄與自動同步；不在下次啟動反覆自動開啟，首頁保留手動重試入口。
- `SleepTracker` 向 Google Play services 訂閱 Sleep API，取得睡眠區段與分類樣本；需要活動辨識權限。
- 接收 Sleep API 區段後排入背景分析工作。分類樣本只先保存，不因每個分類事件立即重跑全部分析。
- 偵測時段內先等待 Sleep API 分類；目前時段內最近 20 分鐘的分類信心值 ≥ 80 時，才啟動該時段的加速度計取樣。80 是未校準的工程門檻，不代表準確率；分類可能約每 10 分鐘才回報、延遲或漏失。觸發後取樣到時段結束，不因後續單次低分反覆停止。
- `SleepAnalyzer` 保留至少 30 分鐘且與時段重疊的區段，合併並扣除已知手機使用；扣除後不足 30 分鐘不產生候選。
- Sleep API 區段會與每日排程時段取交集後才形成候選；跨越多日的長區段會分成各日排程範圍，區段外的時間不採計。加速度計候選也限制在完整時段內。
- Sleep API 參考分數由區段分數 × 45%、高信心分類比例 × 35%、分類覆蓋率 × 20% 組成。每個分類樣本以前後各 5 分鐘估計覆蓋；重疊覆蓋會合併。這是工程規則，不是經驗證的準確率。
- `SleepUpdateReceiver` 將成功區段狀態映射為 100、其他非 NOT_DETECTED 狀態映射為 60；這不是 Google 直接提供的睡眠區段準確率。

### 加速度計與省電

- 完成時段設定及活動辨識授權後自動啟動前景記錄服務；加速度計會等當前時段內的 Google 高信心睡眠分類才啟動。新資料保存為 `AUTO`，由 `AutomaticPlacement` 在分析時推估床上／床邊／未知，不使用舊版手動位置設定。原始分鐘資料仍保留，以便重算。
- 新的 `recording_enabled` 預設 true，取代舊版感測器 `enabled`；原來沒開啟動作感測的使用者升級後也會自動記錄。首頁與通知只保留整體「暫停／恢復自動記錄」，明確暫停後不自動重啟。
- Google 高信心分類觸發後固定要求 1 Hz，接電時也不提高頻率；有硬體 FIFO 時批次延遲依 FIFO 容量 × 取樣間隔 × 80% 換算，不另設 App 時間上限，僅受 Android API `Int` 可表示範圍限制。硬體最小取樣間隔也會限制請求頻率。
- 無 FIFO 時仍為 1 Hz，但不能宣稱有硬體批次。優先選有 FIFO 的 wake-up accelerometer；非 wake-up 感測器休眠時可能缺資料。
- 未接電且電量 ≤ 15% 暫停；接電或電量恢復後重新評估。時段外不取樣，但保留前景服務通知與邊界排程。
- 不使用持續 CPU wake lock，不啟用陀螺儀、麥克風、定位或相機。`play-services-location` 是為了活動／Sleep API，不能據此聲稱有 GPS 定位功能。
- `MotionService` 以 HandlerThread 收感測事件、以事件的單調時間轉成資料時間，不能用批次送達時間取代樣本時間。
- 每分鐘保存覆蓋時間、活動時間、三軸變化 RMS 所需統計、樣本數及放置模式，不保存原始波形。約每 5 分鐘用 SQLite 交易寫入。
- 暫停、切換模式或正常停止先要求 sensor flush，最多等待 2 秒，再保存已收到資料。直接殺死程序可能遺失最後約 5 分鐘未存摘要及未送達批次，不能將缺口補成安靜。
- 每分鐘有效覆蓋至少 45 秒才分類。相鄰三軸差值 ≥ 0.15 m/s² 算活動；活動時間比例 ≥ 5% 或差值 RMS ≥ 0.20 m/s²，該分鐘標示活動。門檻尚未校準。
- 正常前景服務使用 `START_STICKY`；開機／套件更新接收器會在已設定、未暫停且有權限時嘗試恢復服務。App 恢復前景也會自動補啟動，無需感測器按鈕。強制停止及 OEM 背景限制仍可能阻止恢復；不能承諾永不漏記。啟動失敗不會把整體記錄開關自動關閉。
- 使用 `setAndAllowWhileIdle` 的非精準時段邊界鬧鐘，可能延後啟動。沒有精準鬧鐘授權；資料事件也會檢查時段。

### Android 電池限制與小米自啟動

- `power/BackgroundAccess.kt` 每次讀取 `ActivityManager.isBackgroundRestricted` 及 `PowerManager.isIgnoringBatteryOptimizations`，兩者分開判斷。不能把有前景服務、曾開啟設定頁或 Activity 的 resultCode 當成已解除限制。
- 明確受背景限制時先開啟本 App 的應用程式設定，提示電池選「不受限制／無限制」；只是未排除最佳化時，使用系統 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 要求一次豁免。系統頁不可用／SecurityException 時依序退回 App 設定、電池最佳化清單、一般設定，皆失敗則顯示操作路徑。
- Manifest 宣告 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`。`BatteryLife` lint 僅在 `batteryIntents()` 局部抑制並說明原因：整夜本機感測是核心功能，不能以 FCM 或延後工作取代；這不代表已通過 Google Play 審核。既有低頻／批次／低電量暫停策略不因豁免而改變。
- 依 manufacturer／brand 辨識 Xiaomi、Redmi、POCO，提供 `com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity` 入口；不可用時退回本 App 設定及一般設定，並提示搜尋「自啟動」。這是廠商私有入口，不能保證所有 MIUI／HyperOS 都支援。
- **沒有可靠公開 API 確認小米自啟動已開啟**；只記錄引導是否顯示過，不能顯示假的「已授權」勾選。小米入口只在首次引導中使用，首頁不顯示小米自啟動或「App 無法讀取」區塊；電池最佳化豁免也不代表廠商全部省電限制已解除。
- 每次回到首頁重新查詢 Android 電池狀態；已就緒的 Android 電池設定入口收起，被撤回時再次顯示。小米一次性引導不影響候選、同步與記錄開關。

### 動作候選與來源選擇

- `AutomaticPlacement` 在連續有效的分鐘摘要內，以前後各最多 30 分鐘的鄰近資料估計訊號是否隨床面動作變化。至少 20 分鐘資料，出現至少 3 個短動作分鐘且首末相隔至少 8 分鐘，才提供床上證據；短動作定義為 RMS 0.015～1.5 m/s²、活動 200～12,000 ms。
- 至少 25 分鐘幾乎完全靜止（RMS < 0.008、活動為零）視為床邊傾向；其他情況為未知。手機使用前後 2 分鐘、資料不足／缺口及 RMS > 1.5 的劇烈動作會切斷判斷區段。這是未經實機校準的啟發式，不是可靠的物理位置辨識。
- 床邊或未知資料不單獨產生動作睡眠候選，仍可採用 Sleep API 與手機使用紀錄。不能因整晚靜止就直接算整晚睡眠；舊資料的 BED／BEDSIDE 標記保留相容性。
- 床上模式連續安靜 20 分鐘後回推起點，完整區段至少 30 分鐘才成為候選；等每日偵測時段結束才產生。
- 已知手機使用、缺失／覆蓋不足資料會切斷候選；持續活動 5 分鐘也會切斷。短暫翻動不直接視為清醒。
- 每時段只取最長的動作候選，參考分數目前為 50。這可能少算分段睡眠。
- 若有效床上動作資料至少 30 分鐘、涵蓋 Sleep API 候選至少一半，而活動分鐘占比 ≥ 30%，該 API 候選降低 30 分。
- `selectBestSessions` 按調整後分數由高到低保留不重疊候選，平手優先 Sleep API。所有採用的候選進入 `PENDING`，沒有人工確認門檻。

### 畫面

- 首頁保留既有 MaterialCardView 視覺：最近睡眠與歷史、每日時段及整體暫停／恢復、必要權限，以及最後一筆已保存的 Sleep API 睡眠信心與回報時間。該分類分數只讀取本機保存資料，不會為顯示而即時查詢，且不是準確率；睡眠候選的參考分數及判斷理由仍留在自選詳情中。
- 不再顯示放置選擇、動作開關、頻率／FIFO 參數、動作時間軸或手動試算按鈕。前景通知顯示自動睡眠記錄，仍遵守 Android 必要通知要求。
- 主畫面使用 `Theme.SleepTrace.Home` 無 ActionBar，加上單一自訂標題。`enableEdgeToEdge` 搭配 systemBars／displayCutout insets，避免狀態列、瀏海及導覽列遮擋。更新前先取得資料，再同步重建畫面並保留捲動位置；不得重加第二個標題列或固定狀態列高度。

## 4. 自動同步、資料完整性與重試

- 現有狀態為 `PENDING`、`SYNCING`、`SYNCED`、`FAILED`、`SKIPPED`。`NEEDS_REVIEW` 僅是舊資料字串，由 `SyncState.fromStored` 轉成 `PENDING`，不能恢復為執行期狀態。
- `SleepReconcileWorker` 先重新分析，再呼叫 `HealthConnectSync.syncPending()`。立即工作使用唯一名稱 `sleeptrace_reconcile_now`／`APPEND_OR_REPLACE`；定期工作為 `sleeptrace_reconcile`／每 6 小時／`UPDATE`。
- Reconcile 只重新分析最近 48 小時的原始 Sleep API／動作資料，以及與未完成同步紀錄重疊的舊 Sleep API 區段；同一輪將所有 segment 的 UsageStats 時段合併成一次查詢，不再逐 segment 重複掃描。
- 暫時失敗以 10 分鐘起的指數退避重試；時間受系統排程影響，不能承諾即時或精準分鐘數。App 恢復前景、健康授權回傳、時間修正及感測／Sleep API 完成事件也會排入工作。
- 沒有健康寫入授權時保留本機紀錄，待授權後或後續工作自動繼續，不要求逐筆同意。
- `AutomaticSyncQueue` 以 Mutex 避免同程序同步併行，處理 PENDING／FAILED／中斷殘留的 SYNCING。取消例外必須向外傳遞，不可吞成一般失敗。
- 無效起訖、有效睡眠不足 30 分鐘、清醒總時長與明細不一致者自動 `SKIPPED`。沒有任何候選時不憑空建立睡眠。
- `SleepStore` 在外部寫入前以可檢查成功與否的同步 `commit()` 保存 ID／版本；不要改成忽略結果的非同步保存，否則中斷後可能失去去重依據。
- 使用 `updateIfCurrent`，同步舊請求完成時不能覆蓋已修正的新資料。
- `mergeSleepSessions` 保留歷史及穩定 ID；起訖／清醒內容改變時遞增 `revision`，回到 PENDING。內容相同不重傳；手動修正不被自動分析覆蓋。
- 目前候選若同時匹配多筆既有紀錄，會保留既有紀錄而跳過合併，以避免丟失已匯出 ID。這是現有保守處理，不代表已完整解決所有多筆重疊情況。
- 寫入 `SleepSessionRecord`，`Metadata.clientRecordId = session.id`、`clientRecordVersion = revision`。重試保持同一 ID／版本；資料修正才增加版本。
- `normalizedAwake` 負責裁切並合併重疊手機使用區間；`sleepParts` 將整段切成 AWAKE／SLEEPING。本機扣除的手機使用時間與上傳階段必須一致。
- Health Connect 系統健康資料使用說明頁與 Android 13 以下套件可見性已宣告；不是額外的 App 同意流程。

## 5. 程式入口與資料流

下表路徑相對於 `app/src/main/java/com/rsps1008/sleeptrace/`。

| 路徑 | 責任 |
| --- | --- |
| `MainActivity.kt` | 簡化首頁、系統安全間距、自動啟動記錄、權限、時間修正 |
| `power/BackgroundAccess.kt` | Android 電池限制查詢、一次性引導旗標、電池與小米自啟動設定及備援 Intent |
| `sleep/SleepTracker.kt` | Sleep API 訂閱／取消；明確指向接收器的 mutable PendingIntent 用於事件載入 |
| `sleep/SleepUpdateReceiver.kt` | 保存 Sleep API 區段／分類，區段事件排入工作 |
| `sleep/ResubscribeReceiver.kt` | 開機／套件更新後重新訂閱 Sleep API、排程並嘗試恢復自動記錄 |
| `sleep/UsageMonitor.kt` | 查 UsageStats 螢幕互動與前景活動；向前查 24 小時以承接區段起點之前的狀態 |
| `sleep/SleepSchedule.kt`、`sleep/SleepAnalyzer.kt` | 時段重疊與 Sleep API 候選／分數 |
| `sleep/SleepModels.kt`、`sleep/SleepIntervals.kt` | 模型、舊狀態相容、清醒區間與上傳階段切分 |
| `sleep/SleepReconciler.kt` | 匯整來源、選擇候選、合併本機歷史與版本 |
| `motion/MotionEngine.kt` | 純 Kotlin 取樣策略、分鐘聚合、動作分類／候選與分數調整 |
| `motion/AutomaticPlacement.kt` | 純 Kotlin 自動放置／床面動作證據判斷；無手動選擇 |
| `motion/MotionService.kt` | 前景感測服務、電量／供電切換、flush、時段邊界 Receiver |
| `motion/MotionStore.kt` | SQLite 分鐘摘要及 SharedPreferences 動作設定 |
| `motion/MotionTimelineView.kt` | 保留的時間軸繪製工具，首頁已不使用 |
| `data/SleepPreferences.kt`、`data/SleepStore.kt` | 時段設定及 Sleep API 原始事件／睡眠紀錄 |
| `health/AutomaticSyncQueue.kt`、`health/HealthConnectSync.kt` | 自動同步佇列、版本競態保護及 Health Connect 寫入 |
| `health/HealthPrivacyActivity.kt` | 系統健康授權畫面的資料使用說明入口 |
| `work/WorkScheduler.kt`、`work/SleepReconcileWorker.kt` | 排程、分析→同步、重試 |

主資料流：Sleep API Receiver／MotionService → 本機資料 → WorkManager → SleepReconciler → SleepStore → AutomaticSyncQueue → HealthConnectSync → Health Connect。Google Fit 端不在這條已驗證的程式呼叫鏈內。

## 6. 本機儲存與隱私

| 儲存 | 內容 |
| --- | --- |
| DataStore `sleeptrace_settings` | 每日開始／結束分鐘、`tracking_enabled`；目前 configured 與 enabled 共用此旗標 |
| SharedPreferences `sleeptrace_records` | JSON `sessions`；首次讀取會交易式遷移舊版 raw `segments`／`samples` |
| SharedPreferences `sleeptrace_motion` | 整體自動記錄開關 recording_enabled、內部狀態、battery_guide_shown／xiaomi_guide_shown 引導旗標（不是授權狀態）；舊 enabled／placement 不再控制新資料 |
| SQLite `motion.db`／`minutes` | 每分鐘感測統計，以開始時間為主鍵；沒有原始感測波形 |
| SQLite `sleep_events.db`／`segments`、`samples` | Sleep API 原始區段與分類，以時間鍵去重、交易批次寫入及 14 天清理 |

Sleep API 原始事件及動作摘要在新增／寫入時清理 14 天前資料，不是到期即定時刪除；歷史睡眠紀錄會保留。相關資料已在 `app/src/main/res/xml/backup_rules.xml` 與 `app/src/main/res/xml/data_extraction_rules.xml` 排除備份。

App 沒有自己的雲端後端，不讀取其他 App 的健康紀錄。清除本機資料不會刪除 Health Connect 已寫入的紀錄。勿把健康資料、裝置帳號或感測紀錄加入文件／版本控制。

## 7. 建置與測試

在專案根目錄執行：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.11'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug --no-configuration-cache
```

變更特定演算法時可先跑相關測試，例如：

```powershell
.\gradlew.bat testDebugUnitTest --tests 'com.rsps1008.sleeptrace.AutomaticSyncTest' --no-configuration-cache
```

儀器測試 APK：`./gradlew.bat assembleDebugAndroidTest --no-configuration-cache`。執行裝置測試前先用 `adb.exe devices -l` 辨識裝置，所有命令指定 `-s <目標序號>`，不要任意安裝到所有連線實機。

- `app/src/test/java/com/rsps1008/sleeptrace/SleepAnalyzerTest.kt`：手機使用扣除、分類不足仍自動同步、一般候選。
- `app/src/test/java/com/rsps1008/sleeptrace/MotionEngineTest.kt`：Google 分類觸發門檻、1 Hz／FIFO、批次時間、資料缺口、床邊／手機使用、跨午夜、動作衝突等。
- `app/src/test/java/com/rsps1008/sleeptrace/AutomaticPlacementTest.kt`：自動放置證據、單次震動、完全靜止、手機使用、缺口、位置變化與舊資料相容。
- `app/src/test/java/com/rsps1008/sleeptrace/AutomaticSyncTest.kt`：舊狀態、自動寫入、重試／取消、中斷恢復、版本競態、來源選擇與清醒切分。
- `app/src/androidTest/java/com/rsps1008/sleeptrace/MotionRuntimeTest.kt`：舊感測設定升級後等待 Google 分類、分類觸發 1 Hz 取樣、首頁單一標題及系統安全間距、不顯示感測器選項、AUTO 摘要、低電量暫停／接電恢復／整體停止。會改 App 時段、授權並模擬分類及電量，**只在可丟棄模擬器執行**。
- `app/src/androidTest/java/com/rsps1008/sleeptrace/AutoSyncStorageTest.kt`：實際 SharedPreferences 遷移、重讀、重試及版本保存；會取消 App 的工作並暫時替換紀錄，**只在可丟棄模擬器執行**。寫入端是替身，不是實際健康服務。
- `app/src/androidTest/java/com/rsps1008/sleeptrace/BackgroundAccessRuntimeTest.kt`：系統設定返回／拒絕後仍自動記錄、不重複跳轉、電池豁免與明確限制狀態更新；會修改測試 App 的 allowlist／AppOps，**只在可丟棄模擬器執行**。系統授權視窗以 ActivityMonitor 模擬取消。
- 純文件修改不需重跑 Android 建置；應核對路徑、敘述與既有測試證據。

Debug APK：`app/build/outputs/apk/debug/app-debug.apk`。JVM 測試結果：`app/build/test-results/testDebugUnitTest/`。Lint 報告：`app/build/reports/lint-results-debug.xml` 與 `.html`。

## 8. 驗證基線與尚未完成事項

截至 2026-09-28，本次自動放置／簡化首頁版本：35 個 JVM 測試通過（SleepAnalyzer 3、MotionEngine 13、AutomaticPlacement 7、AutomaticSync 11、既有範例 1），Lint 零問題，Debug APK 與測試 APK 建置成功。唯讀 Pixel_10_Pro 模擬器的 MotionRuntimeTest 通過，已驗證首頁自動啟動感測、標題完整且避開系統列、AUTO 摘要與電池切換；截圖目視確認標題未遮擋。測試時模擬器曾出現 System UI 無回應視窗，排除後重新截圖正常。先前自動同步版本的 AutoSyncStorageTest 通過、健康資料使用說明頁成功啟動，屬歷史驗證，本次未重跑。後續程式變更後不能直接宣稱仍然通過。

2026-09-28 電池限制／小米自啟動引導更新：35 個 JVM 測試通過、Lint 無未處理問題（BatteryLife 的局部理由見上）、Debug APK 與測試 APK 建置成功。唯讀模擬器的 BackgroundAccessRuntimeTest 與 MotionRuntimeTest 共 2 個測試通過，含拒絕後仍記錄、不重複跳轉、允許／撤回／明確限制後返回更新及既有省電服務行為。Mi Note 10 僅以唯讀 `resolve-activity` 確認自啟動入口存在，未安裝或更動實機設定，尚未驗證 MIUI／HyperOS 的完整操作及整夜背景恢復。

2026-09-29 Google 分類觸發／1 Hz 更新：36 個 JVM 測試通過，Lint 無未處理問題，Debug APK 與測試 APK 建置成功。新增目前時段、信心門檻與事件新鮮度測試，並將既有 MotionEngine 取樣測試改為 1 Hz。當時只連接 Mi Note 10 與 Pixel 實機，依規則未安裝或執行會變更授權、時段及模擬電量的 MotionRuntimeTest；Google 實際分類觸發、Doze／FIFO、整夜耗電及準確度仍未經實機驗證。

尚未完成或不能保證的項目：

- 實機整夜耗電、真實 FIFO 行為、長時間 Doze／OEM 背景限制與實際入睡準確度尚未量測。先前討論的耗電百分比是估算，不是實測結果。
- 實際授權後的 Health Connect 寫入、Google Fit 端讀取／顯示尚未完成端到端驗證。單元測試或替身成功不能替代此證據。
- 沒有 PSG 或穿戴裝置對照驗證，也沒有深眠／淺眠／REM 分期。
- 床墊、床伴、手機震動、手機離人太遠或人離床都可能影響動作推估；自動放置判斷只是訊號啟發式，準確度尚未量測，不能宣稱精確知道床上／床邊。
- 還沒有 Google Play 上架／正式簽署發行的完成證據；Debug APK 不是正式發行版本。

## 9. 後續修改原則

- 用繁體中文回報實際變更、測試結果與未驗證範圍。先看相關來源及既有變更，保留不相關檔案。
- 不要恢復人工確認門檻、把參考分數包裝成準確率，或把未回報／缺失資料補成睡眠。
- 修改同步時保留穩定 ID、遞增版本、取消傳遞及舊請求不得覆蓋新版本的保護；修改資料格式需相容既有紀錄。
- 修改取樣、前景服務或排程時需同時考量省電與資料缺口，說明真機驗證缺口。不要為了資料完整偷偷加入持續喚醒、高頻感測或精準鬧鐘需求。
- 保持本文件、README 與畫面描述一致；較完整操作及量測方式見 `README.md`。本次目錄在 `E:\Git\sleep`，不屬於使用者規定需更新 `E:\DailyDev.csv` 的 `E:\Git\HH` 範圍。
