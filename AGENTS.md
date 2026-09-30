# 眠迹 SleepTrace：專案接手指南

本文件供後續對話、AI 與開發者接手使用，適用於本專案全目錄。內容依 2026-09-30 的程式與已完成驗證整理；後續修改功能時，請同步維護本文件及 `README.md`。若描述與程式不同，先查實作並說明差異，不要把規劃或舊對話當作已完成功能。使用者後續明確指示優先於本文件。

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
- 單筆有效 SleepSession 內可重算 AWAKE／LIGHT／DEEP／SLEEPING 工程推估，不辨識 REM、不是醫療用途且未經 PSG 驗證。手機靜止不是入睡證據；BEDSIDE、缺少有效動作不推 Deep；無耦合支持不能細分深淺；有近期支持的 HELD 可有限延續，不因單純安靜刷新。仍要求 1 Hz，App 再以固定秒桶正規化 callback，分期只讀既有分鐘摘要。

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
| 資料保存 | Preferences DataStore、SharedPreferences 動作設定、SQLiteOpenHelper；睡眠事件與紀錄統一在 SQLite，尚未使用 Room |
| 健康／活動 SDK | Health Connect 1.1.0、play-services-location 21.4.0 |

版本依據為 `app/build.gradle.kts`、`gradle/libs.versions.toml`、`gradle/wrapper/gradle-wrapper.properties`。目前只套用 `com.android.application` 外掛並使用 AGP 內建 Kotlin 支援，不要因一般舊版範本而補上重複 Kotlin Android 外掛或 Java 25 toolchain。

這是 Android Gradle 專案，不使用 Maven。2026-09-28 根目錄未見 `.git`；後續 Git 操作前先查當時狀態，不要假設已存在分支、遠端或提交。

## 3. 功能與判斷規則

### 偵測時段及 Sleep API

- 首次開啟要求設定偵測時段，設定前 `configured()` 為 false，不啟用睡眠分析。平日可使用每日時段；可選擇為週六／週日另設時段，跨午夜依睡眠窗開始日決定套用哪組。起訖相同時目前視為 24 小時。開始與結束時間在同一個對話框以 24 小時制拉選欄位選取，並即時標示跨夜狀態；不使用時鐘式選擇器。國定假日不會自動判斷。
- `MainActivity` 每次啟動會自動檢查活動辨識、通知、Health Connect 與使用情況存取；可由 App 發起的權限會直接啟動系統授權流程，使用情況存取則帶到 Android 系統設定頁。畫面保留狀態與重新檢查入口，不要求使用者逐項尋找設定按鈕。
- 完成上述流程及時段設定後，若未暫停，會引導背景電池設定與小米自啟動；若 Android 12+ 尚未允許「鬧鐘與提醒」，也會說明睡眠窗限定服務需要此特殊存取。即使略過，睡眠窗外也必須停止 FGS；非精準鬧鐘／Sleep API 回呼只可盡力啟動，Android 可能拒絕或延遲，首頁提示可能漏掉動作資料。Sleep API 區段同步仍可運作，使用者開啟 App 時會補啟動。
- `SleepTracker` 全天訂閱 Sleep API 睡眠區段；睡眠窗開始前 15 分鐘至窗結束才額外訂閱週期性分類事件，使用 `SEGMENT_AND_CLASSIFY_EVENTS`，其餘時間使用 `SEGMENT_EVENTS_ONLY`。預熱分類可在睡眠窗開始時觸發取樣，但 FGS 與加速度計仍只在實際睡眠窗內啟動；需要活動辨識權限。
- 接收 Sleep API 區段後排入背景分析工作。分類樣本只先保存，不因每個分類事件立即重跑全部分析。
- 睡眠窗前 15 分鐘開始預熱 Sleep API 分類；目前睡眠窗或其前 15 分鐘內、最近 20 分鐘的分類信心值 ≥ 80 時，才在實際睡眠窗內啟動該時段的加速度計取樣。若時段開始已過 2 小時仍未觸發、螢幕亦已持續關閉至少 2 小時，啟動同樣 1 Hz 的低頻動作備援，避免 Google 回報延遲造成整夜空窗；這不是靜止或睡眠證明。80 與 2 小時都是未校準工程門檻，不代表準確率；分類可能約每 10 分鐘才回報、延遲或漏失。觸發後取樣到時段結束，不因後續單次低分反覆停止。
- `SleepAnalyzer` 保留至少 30 分鐘且與時段重疊的區段。只在完整睡眠窗結束後以完整起訖查詢一次 UsageStats，再以 `(windowStart, windowEnd)` 複合鍵保存於 `sleep_events.db`，供 `AutomaticPlacement`、`SleepAnalyzer` 與 Health Connect 共用；排程結束時間改變時必須查詢新窗口，不能沿用舊 snapshot。未結束窗口不可保存半窗 snapshot、產生或上傳候選。缺少使用情況權限時按窗口保存不可用標記；理由只使用該睡眠窗的權限狀態及手機使用區間。
- Sleep API 區段會與平日／週末排程取交集後才形成候選；跨越多日的長區段會分成各睡眠窗範圍，區段外的時間不採計。加速度計候選也限制在完整睡眠窗內。
- Sleep API 參考分數由區段分數 × 45%、高信心分類比例 × 35%、分類覆蓋率 × 20% 組成。每個分類樣本以前後各 5 分鐘估計覆蓋；重疊覆蓋會合併。這是工程規則，不是經驗證的準確率。
- `SleepUpdateReceiver` 將成功區段狀態映射為 100、其他非 NOT_DETECTED 狀態映射為 60；這不是 Google 直接提供的睡眠區段準確率。

### 加速度計與省電

- 完成時段設定及活動辨識授權後，在排定睡眠窗內啟動前景記錄服務，窗外一律停止；開始／結束邊界由獨立 AlarmManager receiver 管理。「鬧鐘與提醒」可準時喚醒邊界。沒有特殊存取時只設非精準鬧鐘並在邊界／分類回呼嘗試啟動，背景限制可能造成延遲或漏記，不能為了自動恢復而回退全天 FGS。新資料保存為 `AUTO`，由 `AutomaticPlacement` 在分析時推估床上／床邊／未知，不使用舊版手動位置設定。
- 新的 `recording_enabled` 預設 true，取代舊版感測器 `enabled`；原來沒開啟動作感測的使用者升級後也會自動記錄。首頁與通知只保留整體「暫停／恢復自動記錄」，明確暫停後不自動重啟。
- Google 高信心分類觸發後固定要求 1 Hz，接電時也不提高頻率；有硬體 FIFO 時批次延遲依 FIFO 容量 × 取樣間隔 × 80% 換算，不另設 App 時間上限，僅受 Android API `Int` 可表示範圍限制。硬體最小取樣間隔也會限制請求頻率。
- 無 FIFO 時仍為 1 Hz，但不能宣稱有硬體批次。優先選有 FIFO 的 wake-up accelerometer；非 wake-up 感測器休眠時可能缺資料。
- 未接電且電量 ≤ 15% 暫停；接電或電量恢復後重新評估。服務只動態監聽 `ACTION_BATTERY_LOW`／`ACTION_BATTERY_OKAY` 與接／斷電事件；需要精確電量時才以一次性的 `ACTION_BATTERY_CHANGED` 快照讀取。`ACTION_SCREEN_ON/OFF` 只更新記憶體中的螢幕狀態，不重讀 DB、電量或排程。
- 不使用持續 CPU wake lock，不啟用陀螺儀、麥克風、定位或相機。`play-services-location` 是為了活動／Sleep API，不能據此聲稱有 GPS 定位功能。
- `MotionService` 以 HandlerThread 收感測事件、以事件的單調時間轉成資料時間，不能用批次送達時間取代樣本時間。
- 每分鐘保存覆蓋時間、活動時間、三軸變化 RMS 所需統計、樣本數及放置模式，不保存原始波形。約每 5 分鐘用 SQLite 交易寫入。
- 首頁可選日期並透過 Android 文件建立器匯出 `motion.db` 分鐘摘要 CSV，含本地時間、覆蓋秒數、活動秒數、RMS、樣本數、feature version、resolved placement、motion level、nightly percentiles、stage/事件、baseline 資訊、版本／放置計數、Deep enter/exit 次數及 `staging_motion_role`。`valid_motion_minute_percent` 表示有效 current-feature 分鐘比例，`sensor_coverage_percent` 依 coveredMillis 估算感測覆蓋；partial minute 與 Awake 相交按時間比例近似。逾 14 天可能已清理，未落盤資料不會由匯出補回。
- 暫停、切換模式或正常停止先要求 sensor flush，最多等待 2 秒，再保存已收到資料。直接殺死程序可能遺失最後約 5 分鐘未存摘要及未送達批次，不能將缺口補成安靜。
- 每分鐘有效覆蓋至少 45 秒才分類。相鄰三軸差值 ≥ 0.15 m/s² 算活動；活動時間比例 ≥ 5% 或差值 RMS ≥ 0.20 m/s²，該分鐘標示活動。門檻尚未校準。
- 睡眠窗內的前景服務使用 `START_STICKY`；開機／套件更新接收器只會在目前處於睡眠窗時嘗試恢復。App 恢復前景也只會在睡眠窗內補啟動，無需感測器按鈕。強制停止、未授予鬧鐘特殊存取及 OEM 背景限制仍可能阻止恢復；不能承諾永不漏記。啟動失敗不會把整體記錄開關自動關閉。
- 有特殊存取時用 `setExactAndAllowWhileIdle` 啟動睡眠窗，鬧鐘只在下一個邊界變更時重設；沒有特殊存取時設非精準鬧鐘並嘗試在窗口啟動 FGS。系統拒絕背景啟動時會保留缺口狀態，不得改用全天 FGS。系統強制停止、OEM 限制或拒絕鬧鐘權限仍可能延遲／漏記，資料事件也會檢查實際平日／週末睡眠窗。

### Android 電池限制與小米自啟動

- `power/BackgroundAccess.kt` 每次讀取 `ActivityManager.isBackgroundRestricted` 及 `PowerManager.isIgnoringBatteryOptimizations`，兩者分開判斷。不能把有前景服務、曾開啟設定頁或 Activity 的 resultCode 當成已解除限制。
- 明確受背景限制時先開啟本 App 的應用程式設定，提示電池選「不受限制／無限制」；只是未排除最佳化時，使用系統 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 要求一次豁免。系統頁不可用／SecurityException 時依序退回 App 設定、電池最佳化清單、一般設定，皆失敗則顯示操作路徑。
- Manifest 宣告 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`。`BatteryLife` lint 僅在 `batteryIntents()` 局部抑制並說明原因：整夜本機感測是核心功能，不能以 FCM 或延後工作取代；這不代表已通過 Google Play 審核。既有低頻／批次／低電量暫停策略不因豁免而改變。
- 依 manufacturer／brand 辨識 Xiaomi、Redmi、POCO，提供 `com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity` 入口；不可用時退回本 App 設定及一般設定，並提示搜尋「自啟動」。這是廠商私有入口，不能保證所有 MIUI／HyperOS 都支援。
- **沒有可靠公開 API 確認小米自啟動已開啟**；只記錄引導是否顯示過，不能顯示假的「已授權」勾選。小米入口只在首次引導中使用，首頁不顯示小米自啟動或「App 無法讀取」區塊；電池最佳化豁免也不代表廠商全部省電限制已解除。
- 每次回到首頁重新查詢 Android 電池狀態；已就緒的 Android 電池設定入口收起，被撤回時再次顯示。小米一次性引導不影響候選、同步與記錄開關。首頁 DataStore、Health Connect 權限與背景狀態查詢由 `HomeViewModel` 在 `Dispatchers.IO` 收集。

### 動作候選與來源選擇

- `AutomaticPlacement` 使用只向後看的耦合證據：至少 20 分鐘連續摘要、30 分鐘內至少 3 個合理短動作且首末相隔 8 分鐘，建立 SUPPORTED；其後安靜維持 HELD 最多 45 分鐘，只有新觀測到的合格動作刷新期限。使用、拿起／姿態突變、缺口、錄製／版本／睡眠窗邊界切斷。只是訊號耦合啟發式，不宣稱物理位置辨識。
- 動作證據門檻會以鄰近、無活動分鐘 RMS 的低分位估計局部底噪，並採 max(絕對下限、底噪倍數) 的保守門檻；它不是設備／床墊校準，無準確度提升保證，原有覆蓋、時長與活動限制仍適用。
- 完全靜止從未建立支持者維持 INSUFFICIENT／UNKNOWN，不因此宣稱床邊或睡眠。舊 BED／BEDSIDE 可讀；v4 固定尺度 BED 摘要有明確相容分期路徑，不重建已丟失的秒級特徵，不能與 v5 混入同一基準。
- 床邊或未知資料不單獨產生動作睡眠候選，仍可採用 Sleep API 與手機使用紀錄。不能因整晚靜止就直接算整晚睡眠；舊資料的 BED／BEDSIDE 標記保留相容性。
- v5 時間結構與 v4 cadence-anchor 相容路徑的 BED／QUIET 可以累積 20 分鐘以形成 motion-only 安靜區段；v3 activity-only 的 BED／ACTIVE 可作為結束區段／衝突證據，但 v3 QUIET 與 v1／v2 不建立或延長候選。motion-only 靜止不得獨立建立有效睡眠候選；Reconciler 需用 Sleep API 區段或 confidence ≥ 80 的分類確認起點，並裁掉證據之前的安靜時間。只有確認後且完整至少 30 分鐘才作為備援候選。
- 已知手機使用、缺失／覆蓋不足資料會切斷候選；持續活動 5 分鐘也會切斷。短暫翻動不直接視為清醒。
- 每個合格的動作安靜段各自形成候選，參考分數目前為 50；同一時段的分段睡眠不再只保留最長一段。
- 若有效床上動作資料至少 30 分鐘、涵蓋 Sleep API 候選至少一半，而活動分鐘占比 ≥ 30%，該 API 候選降低 30 分。
- `selectBestSessions` 按調整後分數由高到低保留不重疊候選，平手優先 Sleep API。所有採用的候選進入 `PENDING`，沒有人工確認門檻。

### 畫面

- 首頁保留既有 MaterialCardView 視覺：最近睡眠與最多 4 筆歷史、每日時段及整體暫停／恢復、必要權限，以及最後一筆已保存的 Sleep API 睡眠信心與回報時間。首頁只查最近 5 筆 session 摘要、不載入 awakeIntervals JSON；歷史清單由 RecyclerView 分頁載入摘要，點選後才按 ID 讀取詳情與清醒區間。該分類分數只讀取本機保存資料，不會為顯示而即時查詢，且不是準確率；睡眠候選的參考分數及判斷理由仍留在自選詳情中。歷史超過首頁摘要上限時提供「查看全部紀錄」清單。
- 不再顯示放置選擇、動作開關、頻率／FIFO 參數、動作時間軸或手動試算按鈕。首頁不顯示複雜感測圖；自選睡眠詳情可顯示簡約時段條，以色塊標記睡眠範圍及已扣除的手機使用／清醒區間。前景通知顯示自動睡眠記錄，仍遵守 Android 必要通知要求。
- 主畫面使用 `Theme.SleepTrace.Home` 無 ActionBar，加上單一自訂標題。`enableEdgeToEdge` 搭配 systemBars／displayCutout insets，避免狀態列、瀏海及導覽列遮擋。Activity 建立時一次建立首頁卡片骨架，資料更新只改文字、Badge 與 visibility，保留捲動位置，不得以 `removeAllViews()` 重建整頁、重加第二個標題列或固定狀態列高度。單筆詳情顯示 AWAKE／LIGHT／DEEP／SLEEPING 時間軸、各階段約略時長及非醫療推估說明，顏色沿用主題／動態色彩。睡眠詳情、排程與時間修正對話框由 `SleepDialogHelper.kt` 管理；時間修正同一對話框同時選兩個時間並顯示時長。

## 4. 自動同步、資料完整性與重試

- 現有狀態為 `PENDING`、`SYNCING`、`SYNCED`、`FAILED_RETRYABLE`、`FAILED_PERMANENT`、`SKIPPED`、`RETIRED`、`RETIRED_FAILED_PERMANENT`。舊 `FAILED` 轉成 `FAILED_RETRYABLE`；`NEEDS_REVIEW` 轉成 `PENDING`，兩者都不是執行期狀態。
- `SleepReconcileWorker` 先重新分析，再呼叫 `HealthConnectSync.syncPendingOutcome()`；Health Connect 退休刪除及新增寫入以程序內 Mutex 序列化，確保可能已在途的舊寫入完成後才刪除其 clientRecordId。暫態錯誤回傳 retry，永久性錯誤保留 `FAILED_PERMANENT` 並回傳 failure，取消例外仍向外傳遞。永久 batch failure 會逐筆 fallback，只有單筆仍失敗的 session 才標成永久失敗。立即工作使用唯一名稱 `sleeptrace_reconcile_now`／`KEEP`；每日 recovery 為 24 小時週期、6 小時 flex、`BatteryNotLow` constraint／`UPDATE`。睡眠窗結束仍會排入一次立即整理。
- Reconcile 只重新分析最近 48 小時的原始 Sleep API／動作資料，以及仍為 `PENDING`、`SYNCING` 或 `FAILED_RETRYABLE` 的舊 session；`FAILED_PERMANENT` 不再拉長歷史掃描範圍。規則版本升級時，另對此近期範圍內、完整落在已結束排程睡眠窗且未手動修正的自動 session 執行撤銷核對：新版候選仍匹配者保留；不再產生且未同步者保留本機列並標成 `SKIPPED`；已同步或可能已送出的 `SYNCED`／`SYNCING`／`FAILED_RETRYABLE`／`FAILED_PERMANENT` 保留列並標成 `RETIRED`，由 Health Connect 刪除流程處理。手動修正、未結束窗口及窗口外資料不受這項撤銷影響。只對已結束的排程睡眠窗查詢完整 UsageStats，並以該窗自己的權限狀態與 snapshot 分析；未結束窗口延後處理。
- 暫時失敗以 10 分鐘起的指數退避重試；時間受系統排程影響，不能承諾即時或精準分鐘數。App 恢復前景、健康授權回傳、時間修正及感測／Sleep API 完成事件也會排入工作。
- 沒有健康寫入授權時保留本機紀錄，待授權後或後續工作自動繼續，不要求逐筆同意。
- `AutomaticSyncQueue` 以 Mutex 避免同程序同步併行，只自動選取 PENDING／FAILED_RETRYABLE／中斷殘留的 SYNCING；`FAILED_PERMANENT` 不會被週期工作重新送出。權限恢復時可將永久寫入失敗恢復為 PENDING，詳情頁亦提供手動重試。取消例外必須向外傳遞，不可吞成一般失敗。
- 合格 session 以 Health Connect 批次寫入，每批最多 1,000 筆；先保存該批 SYNCING，批次成功後逐筆以 `updateIfCurrent` 標記 SYNCED。暫態 batch failure 將該批設為 `FAILED_RETRYABLE` 並停止；永久 batch failure 改逐筆寫入，成功列照常 `SYNCED`，單筆仍失敗才按錯誤類型保存重試狀態。新 session 寫入 AWAKE／LIGHT／DEEP／SLEEPING；缺少細分證據及舊紀錄空白都保留 SLEEPING。
- 無效起訖、有效睡眠不足 30 分鐘、清醒總時長與明細不一致者自動 `SKIPPED`。沒有任何候選時不憑空建立睡眠。
- `SleepStore` 在外部寫入前以可檢查成功與否的同步 `commit()` 保存 ID／版本；不要改成忽略結果的非同步保存，否則中斷後可能失去去重依據。時間修正、永久失敗手動重試、Health Connect 權限恢復與 UsageStats 權限由無到有都更新 work generation／dirty flag，避免 `KEEP` 合併時遺失變更。時間修正只保存新的範圍；UsageStats 依 `(windowStart, windowEnd)` 保存為一次 snapshot，再由分析與上傳共用，不在各階段重新查詢。
- 使用 `updateIfCurrent`，同步舊請求完成時不能覆蓋已修正的新資料。
- `mergeSleepSessions` 保留歷史及穩定 ID；起訖／清醒／分期內容改變時遞增 `revision`，已同步紀錄回到 PENDING。內容相同不重傳；手動修正的時間不被自動分析覆蓋，但分期會依修正後區間重新計算。
- 新候選若同時匹配多筆既有紀錄，保留一筆既有穩定 ID 並遞增版本作為新版；其餘未同步碎片直接取代，已同步或可能已送出的碎片標記為 `RETIRED`，Health Connect 成功依 clientRecordId 移除後才繼續送出新版。規則升版撤銷的未同步列標為 `SKIPPED` 並保留本機歷史；已同步或可能已送出的列使用相同 `RETIRED` 刪除流程，不直接刪除本機列。刪除失敗會保留 `RETIRED` 並重試，避免留下重複遠端資料。
- 寫入 `SleepSessionRecord`，`Metadata.clientRecordId = session.id`、`clientRecordVersion = revision`。重試保持同一 ID／版本；資料修正才增加版本。
- `normalizedAwake` 負責裁切並合併重疊手機使用區間；`sleepParts` 將 session 標準化為 AWAKE／LIGHT／DEEP／SLEEPING；部分空白或矛盾分期回 SLEEPING，清醒證據優先。Health Connect 1.1.0 支援並使用 `STAGE_TYPE_AWAKE`／`STAGE_TYPE_LIGHT`／`STAGE_TYPE_DEEP`／`STAGE_TYPE_SLEEPING` 常數；不使用 hardcode stage 數值。本機扣除的手機使用時間與上傳階段必須一致。
- Health Connect 系統健康資料使用說明頁與 Android 13 以下套件可見性已宣告；不是額外的 App 同意流程。

## 5. 程式入口與資料流

下表路徑相對於 `app/src/main/java/com/rsps1008/sleeptrace/`。

| 路徑 | 責任 |
| --- | --- |
| `SleepTraceApplication.kt` | application-scoped 依賴容器；集中提供設定、睡眠資料、動作設定、背景存取及 Health Connect 同步元件給 UI、Worker、Receiver、Service，並於 Android 支援時套用動態色彩 |
| `MainActivity.kt`、`HomeViewModel.kt`、`SleepDialogHelper.kt` | 簡化首頁、系統安全間距、自動啟動記錄、權限與歷史清單；ViewModel 負責首頁資料、授權與背景狀態彙整，Dialog helper 負責排程、詳情及單一時間修正，Activity 觀察並繪製 |
| `power/BackgroundAccess.kt` | Android 電池限制查詢、一次性引導旗標、電池與小米自啟動設定及備援 Intent |
| `sleep/SleepTracker.kt` | Sleep API 全天區段訂閱及睡眠窗前 15 分鐘至窗結束的分類訂閱；明確指向接收器的 mutable PendingIntent 用於事件載入 |
| `sleep/SleepUpdateReceiver.kt` | 保存 Sleep API 區段／分類，區段事件排入工作 |
| `sleep/ResubscribeReceiver.kt` | 開機／套件更新後重新訂閱 Sleep API、排程並嘗試恢復自動記錄 |
| `sleep/UsageMonitor.kt` | 查 UsageStats 螢幕互動與前景活動；向前查 24 小時以承接區段起點之前的狀態，並在裝置關機／啟動事件結算與清除跨 boot 狀態 |
| `sleep/SleepSchedule.kt`、`sleep/SleepAnalyzer.kt` | 時段重疊與 Sleep API 候選／分數 |
| `sleep/SleepStageEstimator.kt` | 僅在已成立 session 內以睡前 UsageStats guard、BED nightly percentiles、inclusive 15 分鐘 rolling median 與 hysteresis 重算 Light／Deep；提供匯出診斷，不保存原始波形 |
| `sleep/SleepUsageSnapshot.kt` | 每個排程睡眠窗一次的 UsageStats 快照；以窗口起訖複合鍵重用，分析與理由依各窗權限狀態分開處理 |
| `sleep/SleepModels.kt`、`sleep/SleepIntervals.kt` | 模型、舊狀態相容、清醒區間與 四種階段的標準化、精確清醒覆蓋與毫秒統計 |
| `sleep/SleepReconciler.kt` | 匯整來源、以 API 證據確認動作候選起點、選擇候選、重算 stage intervals、合併本機歷史與版本 |
| `motion/MotionEngine.kt` | 純 Kotlin 取樣策略、事件時間 1 秒代表 sample、分鐘聚合、動作分類／候選與分數調整 |
| `motion/AutomaticPlacement.kt` | 純 Kotlin 自動放置／床面動作證據判斷；無手動選擇 |
| `motion/MotionService.kt` | 睡眠窗內前景感測服務、電量／供電切換、flush 與快取設定；螢幕事件只更新記憶體狀態 |
| `motion/SleepWindowScheduler.kt` | 睡眠窗開始／結束及分類備援邊界；未變更的下一個邊界不重設鬧鐘 |
| `motion/MotionStore.kt` | SQLite 分鐘摘要及 SharedPreferences 動作設定；由 Application 容器共用並啟用 WAL |
| `motion/MotionTimelineView.kt` | 保留的時間軸繪製工具，首頁已不使用 |
| `data/SleepPreferences.kt`、`data/SleepStore.kt`、`data/SleepEventStore.kt` | 平日／週末時段設定、Sleep API 原始事件、每晚 UsageStats snapshot 與睡眠紀錄；snapshot 使用 `(windowStart, windowEnd)` 複合鍵並由資料庫版本遷移舊表；session 查詢支援 limit／offset、摘要 projection、狀態篩選與時間／狀態索引 |
| `data/AutomaticWorkSignals.kt` | automatic-work generation 與最近完成整理 generation；涵蓋原始事件、session 修正／重試及權限恢復，讓 KEEP 工作合併時可偵測期間到達的新變更 |
| `health/AutomaticSyncQueue.kt`、`health/HealthConnectSync.kt` | 自動同步佇列、版本競態保護及 Health Connect 寫入 |
| `health/HealthPrivacyActivity.kt` | 系統健康授權畫面的資料使用說明入口 |
| `work/WorkScheduler.kt`、`work/SleepReconcileWorker.kt` | 排程、分析→同步、重試 |

主資料流：Sleep API Receiver／MotionService → 本機資料 → WorkManager → SleepReconciler → SleepStore → AutomaticSyncQueue → HealthConnectSync → Health Connect。Google Fit 端不在這條已驗證的程式呼叫鏈內。

## 6. 本機儲存與隱私

| 儲存 | 內容 |
| --- | --- |
| DataStore `sleeptrace_settings` | 平日開始／結束、可選週末開始／結束分鐘、`tracking_enabled`；目前 configured 與 enabled 共用此旗標 |
| SharedPreferences `sleeptrace_records` | 僅作為舊版 JSON `sessions`／raw `segments`／`samples` 的一次性遷移來源；遷移完成後移除內容 |
| SharedPreferences `sleeptrace_motion` | 整體自動記錄開關 recording_enabled、鬧鐘邊界快取與每日清理日期、battery_guide_shown／xiaomi_guide_shown／window_alarm_guide_shown 引導旗標（不是授權狀態）；舊 enabled／placement 不再控制新資料 |
| SQLite `motion.db`／`minutes` | 每分鐘感測統計，以開始時間為主鍵；schema 3 保存 nullable v5 時間結構特徵及 capture_runs；舊欄位保留，沒有原始感測波形 |
| SQLite `sleep_events.db`／`segments`、`samples`、`sessions`、`usage_snapshots` | Sleep API 原始區段與分類以時間鍵去重，資料含一次性每日清理檢查及 14 天保留；每晚 UsageStats snapshot 以 `(windowStart, windowEnd)` 複合鍵保存；schema 11 的 session 規則／特徵來源 nullable 欄位；沿用 schema 10 的 evidenceStart 記錄前 30 分鐘 guard 證據，缺少此前置範圍的舊 snapshot 會補查一次；session 保存穩定 ID、版本、同步狀態、清醒明細與合併後的 `stageIntervals` JSON，不保存逐分鐘分期或 accelerometer 波形。兩個 SQLite helper 啟用 WAL；reconcile 只查最近 48 小時及 PENDING／SYNCING／FAILED_RETRYABLE session，差異列以單一 transaction 刪除／upsert，未變更歷史不重寫；`replaceSessions` 僅用於明確完整重算或遷移 |

Sleep API 原始事件及動作摘要在寫入時最多每日檢查並清理一次 14 天前資料，不是到期即定時刪除；歷史睡眠紀錄會保留。相關資料已在 `app/src/main/res/xml/backup_rules.xml` 與 `app/src/main/res/xml/data_extraction_rules.xml` 排除備份。

App 沒有自己的雲端後端，不讀取其他 App 的健康紀錄。清除本機資料不會刪除 Health Connect 已寫入的紀錄。勿把健康資料、裝置帳號或感測紀錄加入文件／版本控制。

## 7. 建置與測試

在專案根目錄執行：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.11'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest assembleRelease --no-configuration-cache
```

變更特定演算法時可先跑相關測試，例如：

```powershell
.\gradlew.bat testDebugUnitTest --tests 'com.rsps1008.sleeptrace.AutomaticSyncTest' --no-configuration-cache
```

儀器測試 APK：`./gradlew.bat assembleDebugAndroidTest --no-configuration-cache`。執行裝置測試前先用 `adb.exe devices -l` 辨識裝置，所有命令指定 `-s <目標序號>`，不要任意安裝到所有連線實機。

- `app/src/test/java/com/rsps1008/sleeptrace/SleepAnalyzerTest.kt`：手機使用扣除、分類不足仍自動同步、一般候選。
- `app/src/test/java/com/rsps1008/sleeptrace/SleepStageEstimatorTest.kt`：平板／手機靜置與 API 起點、15 分鐘手機使用 guard、短翻身、長活動、夜間拿手機、資料缺口、床邊／未知放置及無 motion fallback。
- `app/src/test/java/com/rsps1008/sleeptrace/SleepScheduleTest.kt`：平日／週末睡眠窗、跨午夜歸屬與相鄰窗口邊界。
- `app/src/test/java/com/rsps1008/sleeptrace/SleepUsageSnapshotTest.kt`：手機使用區間套用與原因文字一致、無權限限制說明。
- `app/src/test/java/com/rsps1008/sleeptrace/MotionEngineTest.kt`：Google 分類觸發門檻、1 Hz／FIFO、批次時間、資料缺口、床邊／手機使用、跨午夜、動作衝突等。
- `app/src/test/java/com/rsps1008/sleeptrace/AutomaticPlacementTest.kt`：自動放置證據、單次震動、完全靜止、手機使用、缺口、位置變化與舊資料相容。
- `app/src/test/java/com/rsps1008/sleeptrace/AutomaticSyncTest.kt`：舊狀態、自動寫入、暫態／永久失敗、重試／取消、中斷恢復、版本競態、來源選擇與清醒切分。
- `app/src/androidTest/java/com/rsps1008/sleeptrace/MotionRuntimeTest.kt`：舊感測設定升級後等待 Google 分類、分類觸發 1 Hz 取樣、首頁單一標題及系統安全間距、不顯示感測器選項、AUTO 摘要、低電量暫停／接電恢復／整體停止。會改 App 時段、授權並模擬分類及電量，**只在可丟棄模擬器執行**。
- `app/src/androidTest/java/com/rsps1008/sleeptrace/AutoSyncStorageTest.kt`：實際舊版 SharedPreferences 至 SQLite 遷移、重讀、重試及版本保存；會取消 App 的工作並暫時替換紀錄，**只在可丟棄模擬器執行**。寫入端是替身，不是實際健康服務。
- `app/src/androidTest/java/com/rsps1008/sleeptrace/BackgroundAccessRuntimeTest.kt`：系統設定返回／拒絕後仍自動記錄、不重複跳轉、電池豁免與明確限制狀態更新；會修改測試 App 的 allowlist／AppOps，**只在可丟棄模擬器執行**。系統授權視窗以 ActivityMonitor 模擬取消。
- 純文件修改不需重跑 Android 建置；應核對路徑、敘述與既有測試證據。

Debug APK：`app/build/outputs/apk/debug/app-debug.apk`。JVM 測試結果：`app/build/test-results/testDebugUnitTest/`。Lint 報告：`app/build/reports/lint-results-debug.xml` 與 `.html`。

## 8. 驗證基線與尚未完成事項

截至 2026-09-28，本次自動放置／簡化首頁版本：35 個 JVM 測試通過（SleepAnalyzer 3、MotionEngine 13、AutomaticPlacement 7、AutomaticSync 11、既有範例 1），Lint 零問題，Debug APK 與測試 APK 建置成功。唯讀 Pixel_10_Pro 模擬器的 MotionRuntimeTest 通過，已驗證首頁自動啟動感測、標題完整且避開系統列、AUTO 摘要與電池切換；截圖目視確認標題未遮擋。測試時模擬器曾出現 System UI 無回應視窗，排除後重新截圖正常。先前自動同步版本的 AutoSyncStorageTest 通過、健康資料使用說明頁成功啟動，屬歷史驗證，本次未重跑。後續程式變更後不能直接宣稱仍然通過。

2026-09-28 電池限制／小米自啟動引導更新：35 個 JVM 測試通過、Lint 無未處理問題（BatteryLife 的局部理由見上）、Debug APK 與測試 APK 建置成功。唯讀模擬器的 BackgroundAccessRuntimeTest 與 MotionRuntimeTest 共 2 個測試通過，含拒絕後仍記錄、不重複跳轉、允許／撤回／明確限制後返回更新及既有省電服務行為。Mi Note 10 僅以唯讀 `resolve-activity` 確認自啟動入口存在，未安裝或更動實機設定，尚未驗證 MIUI／HyperOS 的完整操作及整夜背景恢復。

2026-09-29 Google 分類觸發／1 Hz 更新：36 個 JVM 測試通過，Lint 無未處理問題，Debug APK 與測試 APK 建置成功。新增目前時段、信心門檻與事件新鮮度測試，並將既有 MotionEngine 取樣測試改為 1 Hz。當時只連接 Mi Note 10 與 Pixel 實機，依規則未安裝或執行會變更授權、時段及模擬電量的 MotionRuntimeTest；Google 實際分類觸發、Doze／FIFO、整夜耗電及準確度仍未經實機驗證。

2026-09-29 本輪效能、同步、分段睡眠與詳情介面更新：40 個 JVM 測試通過，Lint 無 issue，Debug APK 與測試 APK 建置成功。涵蓋原始 Sleep API 事件 SQLite 保存、排程裁切、上傳前單次手機使用快照、分段動作候選、Google 分類延遲備援、重疊已同步紀錄回收與詳情清醒色塊。未執行會修改授權、時段或資料的 instrumentation test；Health Connect 實際刪除／寫入、Google 分類延遲、Doze／OEM 背景限制、FIFO 與整夜耗電仍未在實機端到端驗證。

2026-09-29 架構整合更新：40 個 JVM 測試通過，Lint 無 issue，Debug APK 與測試 APK 建置成功。首頁資料載入已移到 `HomeViewModel`；睡眠 session、raw segment 與分類統一至 `sleep_events.db`，並在首次讀取時交易式遷移舊版 `sleeptrace_records` JSON。`AutoSyncStorageTest` 已隨新 schema 編譯，但因當時連接的 Mi Note 10 與 Pixel 均為實機，依規則未實跑會寫入／遷移資料的 instrumentation test；實際升級遷移、Health Connect 寫入／刪除和整夜感測仍未端到端驗證。

2026-09-29 資料庫／UI／同步效能更新：43 個 JVM 測試通過、Lint 無 issue，Debug APK 與 Android 測試 APK 建置成功。`SleepStore.updateIfCurrent` 與 `upsert` 已改為單筆 session id 查詢／upsert；首頁與 MotionService 改用最新／近期 classification 查詢；`MotionStore` 納入 Application 容器並與 `SleepEventStore` 啟用 WAL；首頁固定骨架更新、DST 跨日修正、MotionAccumulator 零配置差值、ACTION_TIME_CHANGED 校時補償及 Worker 暫態／永久錯誤分流已完成。當時連接的 Mi Note 10、Pixel 與另一支實機皆非可丟棄模擬器，未執行會修改資料／授權的 instrumentation test；Health Connect 實際寫入／刪除、整夜感測、OEM 背景恢復與耗電仍未端到端驗證。

2026-09-29 背景與 UI 查詢優化：完成 `MotionService` 低電量事件監聽、`SleepReconciler` segment／sample 時間範圍查詢、`SleepStore.reviseTimes` 主鍵查詢、`MotionStore.append` 批次既有資料查詢、`HomeViewModel` 全段 I/O 卸載、時間條圓角裁切、`SleepDialogHelper` 單一時間修正對話框及完整歷史清單入口。`testDebugUnitTest`、`lintDebug`、`assembleDebug`、`assembleDebugAndroidTest` 均通過；未在裝置執行會修改資料／權限的 instrumentation test，真實電量喚醒次數、OEM 背景行為、Health Connect 端到端寫入與整夜耗電仍未驗證。

2026-09-29 session 歷史與同步擴展性更新：首頁改查最新 5 筆摘要並延後載入清醒明細；完整歷史使用 RecyclerView 分頁；session 加入排序／狀態索引；Health Connect 改批次寫入（最多 1,000 筆／批）；自動放置加入局部底噪相對門檻、邊界鬧鐘加安全回退、時間軸套用主題與動態色彩。43 個 JVM 測試通過、Lint 零 issue、Debug APK 與 Android 測試 APK 建置成功。未執行裝置 instrumentation／實際 Health Connect 寫入或 UI 主題目視驗證；底噪門檻仍未以不同手機與床墊校準，深淺眠分期未實作。

2026-09-29 P0／P1／P2 省電與排程更新：完整睡眠窗結束後才查詢並保存每窗一次的 UsageStats snapshot，共用於 AutomaticPlacement、SleepAnalyzer 與 Health Connect；App 偵測權限由無到有時排入整理更新舊 snapshot；Sleep API 窗外只訂閱 segment，睡眠窗前 15 分鐘至結束才訂 classify；永久同步失敗與可重試失敗分流；AlarmManager 只在下一邊界變更時重設，螢幕事件使用快取；立即整理改 KEEP、每日 recovery 加 BatteryNotLow；SQLite retention 改為每日最多清理一次；Release 啟用 R8；支援週末獨立時段。未結束窗口不保存半窗 snapshot、不產生或上傳候選。P2 FGS 已改為任何情況都只在睡眠窗執行；未授予鬧鐘特殊存取時使用非精準鬧鐘並盡力啟動，背景限制可能造成漏記，沒有全天 FGS fallback。53 個 JVM 測試通過；Debug lint 有 30 條 Warning、無 Error／Fatal；Debug APK、Android 測試 APK 與啟用 R8 的 unsigned Release APK 建置成功。沒有在實機驗證鬧鐘權限、背景啟動、Health Connect 或整夜耗電。

2026-09-29 本輪資料庫／刷新／系統邊界效能更新：reconcile 改為最近 48 小時加未完成同步 session 的範圍查詢，差異列在單一 transaction 內刪除／upsert；WorkManager 只在完成狀態刷新，`HomeViewModel` 以 conflated queue 防止並行讀取；`UsageMonitor` 遇到裝置關機／啟動會結算並清除跨 boot 狀態；歷史 RecyclerView 改用精確的 footer／range 通知；FIFO 批次存檔改以 HandlerThread `elapsedRealtime()` 節流，包含裝置深度休眠時間。43 個 JVM 測試通過、Lint task 成功（保留既有 warnings）、Debug APK 與 Android 測試 APK 建置成功。未執行裝置 instrumentation、Health Connect 實際寫入／刪除、UsageStats 真機重啟事件、FIFO 長批次與整夜耗電驗證。

尚未完成或不能保證的項目：

- 實機整夜耗電、真實 FIFO 行為、長時間 Doze／OEM 背景限制與實際入睡準確度尚未量測。先前討論的耗電百分比是估算，不是實測結果。
- 實際授權後的 Health Connect 寫入、Google Fit 端讀取／顯示尚未完成端到端驗證。單元測試或替身成功不能替代此證據。
- 深眠／淺眠只是工程推估，未經 PSG 或穿戴裝置對照驗證；不辨識 REM，不能宣稱醫療準確度。
- 床墊、床伴、手機震動、手機離人太遠或人離床都可能影響動作推估；自動放置判斷只是訊號啟發式，準確度尚未量測，不能宣稱精確知道床上／床邊。
- 還沒有 Google Play 上架／正式簽署發行的完成證據；Debug APK 不是正式發行版本。

2026-09-29 本輪同步可靠性更新：Health Connect 永久 batch failure 改逐筆 fallback，只隔離單筆失敗紀錄；`FAILED_PERMANENT` 不再擴大 reconcile 舊資料範圍；UsageStats snapshot 改用 `(windowStart, windowEnd)` 複合鍵並按每晚權限狀態分析；時間修正、手動重試與權限恢復納入 work generation。完整 `testDebugUnitTest` 59 項通過，`lintDebug`、Debug APK、Android 測試 APK、Release APK 建置成功；未執行會修改裝置資料／權限的 instrumentation test，Health Connect 真實寫入與 SQLite 舊版升級尚未做裝置端驗證。

2026-09-30 非醫療 Light／Deep 推估：新增 `SleepStageEstimator`，僅重算已成立 session 內的合併階段區間；motion-only 靜止候選須由 Sleep API segment 或 confidence ≥ 80 classification 確認起點，手機使用標記 Awake 並重置 continuity，15 分鐘 guard 後以當晚 P35、≥10／15 分鐘低活動窗及短翻身平滑判 Deep。新資料寫入 Health Connect AWAKE／LIGHT／DEEP，舊資料保留 SLEEPING fallback；`sleep_events.db` 升至 schema 9 保存 stageIntervals，詳情新增摘要與三色時間軸，取樣仍為 1 Hz。70 個 JVM 測試通過，lintDebug 有 24 條 Warning、無 Error，assembleDebug 成功。未執行裝置資料庫 migration instrumentation、真實 Health Connect 寫入、PSG／穿戴對照或整夜耗電驗證。

2026-09-30 動作摘要匯出：首頁新增日期選擇及 Android 文件建立器 CSV 匯出，包含本地分鐘時間、覆蓋／活動秒數、合併三軸變化 RMS、樣本數及放置模式。`testDebugUnitTest` 70 項通過；尚未在實機核對指定夜晚的實際資料列。

## 9. 後續修改原則

- 用繁體中文回報實際變更、測試結果與未驗證範圍。先看相關來源及既有變更，保留不相關檔案。
- 不要恢復人工確認門檻、把參考分數包裝成準確率，或把未回報／缺失資料補成睡眠。
- 修改同步時保留穩定 ID、遞增版本、取消傳遞及舊請求不得覆蓋新版本的保護；修改資料格式需相容既有紀錄。
- 修改取樣、前景服務或排程時需同時考量省電與資料缺口，說明真機驗證缺口。睡眠窗限定 FGS 在未獲鬧鐘權限時仍不得退回全天服務；需明確呈現非精準背景啟動的漏記風險。
- 保持本文件、README 與畫面描述一致；較完整操作及量測方式見 `README.md`。每次本專案修改後也要在 `E:\DailyDev.csv` 更新當日 `sleep` 工作紀錄。


## 2026-09-30 第二輪非醫療分期校正（歷史規則 v4）

這段是升級前 v4 的驗證紀錄。當時採 inclusive 15 分鐘／12～15 valid、UNKNOWN 五分鐘 grace 與窄分布安全上限；本次 v5 已更新為完整連續品質窗口、有期限耦合、缺資料未判定及多原因診斷，請依下方 v5 章節與目前程式。歷史測試數不等於目前結果。

新增測試：SleepStageCalibrationTest（callback 1／10／50 Hz、FIFO、缺秒、inclusive window、stay／exit、睡前手機操作、平板案例、UNKNOWN grace、BEDSIDE／缺口、flat signal safety bound、Health Connect 常數與 client ID/version、合成 nightly fixture）；SleepUsageSnapshotTest 補上舊快照前置證據刷新。StagingStorageRuntimeTest 檢查 motion schema 1→2、usage schema 7／8／9→10，以及手動分期 revision／stale update，僅在可丟棄模擬器使用隔離 DB 執行。真實健康 CSV 不加入版本控制。


2026-09-30 第二輪校正驗證：testDebugUnitTest 共 89 項通過、0 failure／error；lintDebug 25 條 Warning、0 Error；assembleDebug、assembleDebugAndroidTest、assembleRelease 全部成功，Release 仍為 unsigned APK。合成 real_night_style fixture 的凍結舊規則產生 3 分鐘 Deep，新規則產生 132 分鐘，僅作工程 regression，不能視為真實生理分期。未在裝置執行 StagingStorageRuntimeTest、實際 Health Connect 寫入、實機整夜耗電或 PSG／穿戴對照。

第二輪演算法版本最初為 2，後續 12/15 視窗正確性修正升為版本 3：AutomaticWorkSignals 將尚未套用的新規則視為 dirty，開啟 App 時會沿用既有 KEEP 工作安排一次最近 48 小時重算。只有本輪完整資料交易完成且 generation 仍相同時才記錄版本已套用；新資料或權限變更仍保留原本 generation 保護。這不增加感測時間或分鐘摘要保存頻率。

2026-09-30 第二輪 correctness／diagnostic 修正：Deep ENTER 將 12～15 valid minute 作為窗口條件，missing／coverage 不足只扣 valid count；舊 feature boundary 仍 hard block，current minute 要求有效 v4 BED／QUIET，backfill 遇 gap 停止。Storage priority 為 v4 > v3 > v1/v2；CSV 採 legacy/current/cadence-incompatible 語意欄位、baseline eligible/current valid counts、valid-minute coverage 與依 coveredMillis 估算的 sensor coverage。current cadence 上限為 1,200 ms。舊 stage 只有零 current valid feature 時保留，並 clip 到 session、填補缺段與 overlay 新 Awake；有 current evidence 則採新結果。Safety cap 新增多 run ranking 與 weakest boundary partial trim regression。109 個 JVM tests 通過；lint 25 warnings、0 errors；Debug、AndroidTest APK、Release build 成功。ADB 只有兩台實體裝置，因此沒有執行僅允許 disposable emulator 的 StagingStorageRuntimeTest；未做實機睡眠校正。

2026-09-30 migration／feature-definition 修正：將 `ALGORITHM_VERSION` 升至 3，使已標記 version 2 的裝置重新整理近期睡眠；featureVersion 4 專指 cadence-anchor 特徵，與曾使用 epoch-second bucket 的 v2 隔離。v1/v2 為 legacy、v3 為 cadence-incompatible、v4 為 current，storage priority v4 > v3 > v2 > v1。CSV session-level 統計改為每 session 預先彙總一次，欄位使用 legacy/current/cadence-incompatible 語意名稱。驗證結果另見本輪回報。

2026-09-30 motion evidence／storage priority／reconcile migration 修正：安靜候選只累積 v4 BED／QUIET；v3 BED／ACTIVE 可結束動作區段或降低既有候選分數，v3 QUIET 及 v1/v2 不提供睡眠正向證據。storage priority 明確為 v4 > v3 > v2 > v1；規則版本升至 4，讓先前已標記版本 3 的裝置重新整理近期 session。全量 `testDebugUnitTest` 113 項通過，lintDebug 25 warnings／0 errors，Debug、AndroidTest APK 與 Release 建置成功；儲存／遷移 instrumentation 僅編譯，未在沒有可丟棄模擬器的環境執行。

2026-09-30 規則遷移撤銷：升版整理會核對近期已完成睡眠窗內未手動修正的自動 session；新版不再產生者保留本機列，未同步列標為 `SKIPPED`，已同步或可能已送出的列標為 `RETIRED` 並走 Health Connect 刪除；手動修正、新候選匹配、未完成睡眠窗與窗口外歷史保留。新增 JVM policy／merge regression 與隔離 SQLite persistence instrumentation。117 個 JVM 測試通過，lintDebug 25 warnings／0 errors，Debug、AndroidTest APK 與 Release 建置成功；SQLite instrumentation 僅編譯，未在實體裝置執行；Health Connect 實際刪除未連服務驗證。

## 2026-09-30 分期規則 v5：未判定、耦合及可觀測特徵

## 2026-09-30 V7 分期證據修正

正式分期規則版本為 7，採集 featureVersion 仍為 5。首次 AUTO 耦合建立保持三個分散短動作、至少 20 個連續 history、30 分鐘回看窗口與八分鐘跨度；成立時不要求當前分鐘有新動作，期限仍取最後正向動作。存活支持可由一個新動作續期；安靜不能續期，失效或反證後單次動作不能復活。邊界會先清狀態，且同分鐘 handling／缺資料仍不可加入新 history。

featureVersion 5 的一個明確短缺口僅在完整 60 秒桶的 `60000-coveredMillis` 不超過 2000 ms 時可作為後續完整分鐘入 Deep 的窗口上下文；最長單次缺口不代表累積缺漏。缺口分鐘自身永遠維持 SLEEPING，最多一個、其餘至少十四個完整合格分鐘且最後五分鐘完整；矛盾、部分桶或資訊不明均不適用。手機使用、handling、長缺口、耦合失效及 recording/version/window 邊界均為硬中斷。`MinuteDiagnostic` 保留 current eligibility、window blockers/intervals、baseline 與 transition diagnostics。詳見 `docs/staging-v7-changes.md`；CSV replay 是 estimator-only，不能驗證 AUTO，也不是醫療或 PSG 證據。

本輪 V7 診斷一致性修正仍不升版：`SleepStageEstimator.evaluateFormalDecision()` 是正式狀態更新與診斷共用的每分鐘入口，保存 `priorState`、current eligibility、entry／maintenance decision、action、transition reason 及高活動窗口計數。正式退出與 `canMaintainDeep` 欄位不能分開重算；前一狀態非 Deep 時，maintenance decision 必須標示不適用，不能用最終 stage 反推。短缺口的 `RECENT_WINDOW_INCOMPLETE`、累積預算阻擋與 `windowBlockingIntervals` 由同一 entry 評估生成；合法缺口離開最近五分鐘後只保留 `ALLOWED_MINOR_GAP` 非阻擋資訊。`formalStage`、`wasBackfilled`、`safetyCapAdjusted` 與 `finalStage` 區分當時正式決策和離線後處理，不新增逐分鐘資料庫寫入。回放 fixture hash 必須從實際讀取的原始 resource bytes 計算；`StagingReplayTest` 預設 check-only，僅 `SLEEPTRACE_UPDATE_REPLAY_DOCS=true` 可更新提交文件。

本次 review 追加的 provenance 為獨立欄位：`priorState=false` 時僅 `entryDecision.applicable=true`，`priorState=true` 時僅 `maintenanceDecision.applicable=true`；未採用分支仍保留 hypothetical `allowed`／reasons 作診斷，不可讀成正式動作。active 3-in-5 回寫只標記真正由 formal Deep 改成 LIGHT／SLEEPING 的歷史分鐘，保存 `retroactivelyAdjusted`、`SUSTAINED_ACTIVITY_REWRITE` 及觸發確認分鐘；觸發分鐘仍由自己的正式 `Action.EXIT` 表示，孤立活動不產生回寫標記。`wasBackfilled`、retroactive activity rewrite、`safetyCapAdjusted` 不互相覆蓋；但現行 pipeline 先把 activity rewrite 的分鐘改為非 Deep，safety cap 只處理剩餘 Deep 分鐘，因此不會由正式流程在同一分鐘同時產生 activity rewrite 與 safety cap，也不得用 `.copy()` 合成該狀態當作流程測試。Hard break 的 `transitionReason` 僅由完整 `maintenanceDecision.reasons` 按 phone → onset → recording boundary → feature boundary → coupling → missing／coverage → no-sleep 固定優先序產生；移除獨立 hard-break 重推論。`currentEligibilityReasons` 只保留令基本分期能力為 false 的原因，ONSET guard 留在 `entryDecision.reasons`，若 prior Deep 且政策觸發維護退出則也留在 `maintenanceDecision.reasons`。CSV 以共用 schema 驗證 header／row 欄位數並新增 retroactive 欄位；`ALGORITHM_VERSION=7`、`MotionAccumulator.CURRENT_FEATURE_VERSION=5` 不變，real_night_style replay 的 intervals／durations／transition matrix 不變。

本輪驗證結果：`testDebugUnitTest` 174 項通過、`lintDebug` 成功且無 Error、`assembleDebug`／`assembleDebugAndroidTest` 成功；`StagingReplayTest` 預設 check-only 通過，沒有覆寫 replay 文件。沒有可丟棄模擬器／測試裝置可安全執行 instrumentation，未執行裝置測試、Health Connect 真實寫入、實機整夜感測或耗電驗證。

以下是目前實作；前面按日期保存的驗證紀錄描述各次歷史版本，不代表目前規則。

四種階段使用同一 `sleepParts()` 時間線：AWAKE 是已知清醒／實際手機使用；LIGHT、DEEP 是有資料能力的工程推估；SLEEPING 是已接受的睡眠 session 內深淺未判定。未成立候選、session 外或排程空白不會補成睡眠。部分 stage 空白、無 motion、無基準、無耦合或低訊號差異不能假裝淺眠。清醒採裁切後聯集，幾秒使用只扣幾秒，首尾清醒保留；矛盾睡眠 stage 重疊回未判定，AWAKE 優先。深 + 淺 + 未判定 = 睡眠，睡眠 + 清醒 = session 跨度，全部先計毫秒。

詳情顯示「推估深眠」「推估淺眠」「深淺未判定」，全晚無細分時說明感測資料不足；未判定時間條有斜線及文字圖例。首頁沒有增加感測器欄位。參考分數不是準確率，也不同於可分期時間。Health Connect 使用 AndroidX 的 STAGE_TYPE_SLEEPING，只有通用睡眠的有效紀錄仍自動同步。成功寫入不等於其他 App 已顯示。

耦合參數集中在 `CouplingPolicy`，均為未校準工程值：近期 30 分鐘至少 20 分鐘資料，3 個分散短動作且首末相隔 8 分鐘；局部安靜底噪乘 3、RMS 絕對下限 0.015 m/s²，活動 0.2～12 秒。手機使用前後 2 分鐘、單次大動作／低頻向量變化 >1.5 m/s²、資料缺口及錄製片段／版本／睡眠窗邊界使支持失效。建立後 HELD 期限 45 分鐘，容許安靜半小時仍有耦合，但不能用安靜永久刷新整晚可信。歷史放置標記保留讀取相容，非物理位置保證；耦合本身絕不是睡眠證據。

`MotionAccumulator.CURRENT_FEATURE_VERSION = 5`，`SleepStageEstimator.ALGORITHM_VERSION = 7`，兩者分別表示摘要定義與推估規則。事件時間 → 帶 100 ms 容許抖動的固定 1 秒代表點 → 摘要 → 離線分期；不累加所有高頻 callback。1 Hz 正式請求及 FIFO 不變，沒有插值，漏一點不補零，長缺口不延伸前值。非有限／重複／倒序事件不改代表點；拒絕數保存在採集摘要。即時與 FIFO 同事件集合得到相同特徵。服務重啟／校時建立不同錄製身份，不跨邊界比較差值。只保存摘要，沒有整晚原始三軸波形。

分鐘新增特徵（舊列為 null；CSV 空白表示沒有測量，與 0 不同）：

| 特徵 | 定義與單位 | 缺漏及邊界 |
| --- | --- | --- |
| covered / missing | 有相鄰事件支持的時間／60000 - covered，ms | 不填補缺漏，首尾部分分鐘保守不細分 |
| longestGapMillis | 相鄰代表點不支持的最長連續事件間隔，ms | 跨分鐘缺口可大於 60000；整晚另累積無資料窗口 |
| RMS / maxDelta | 固定尺度三軸差值時間加權 RMS／最大差值，m/s² | 只計有支持的相鄰點；沒有效差值時 peak 為 null |
| activeMillis / movementEvents | 差值 ≥0.15 m/s² 的時間／由非活動進入活動的分離事件數 | 不是所有未取樣秒內動作的次數；跨分鐘連續活動不重計事件 |
| longestActiveMillis / quietTailMillis | 最長連續活動／分鐘末連續安靜，ms | 延續只跨已觀測有效相鄰點，缺口與重啟歸零；有缺秒的 v5 分鐘不能維持 Deep；可含前一分鐘連續部分 |
| postureDelta | 每 10 個固定尺度代表點的三軸均值，對當分鐘第一組均值的最大變化，m/s² | 只描述低頻向量變化，不宣稱精確姿態；不足兩組或中途缺口不跨接 |
| recordingId / observedStart / observedEnd | 錄製片段身份及有支持差值的事件範圍 | 重複／重疊新摘要不累加；同分钟不同片段合併後禁止細分 |

v4 維持固定 cadence 的相容 RMS 路徑；v1／v2 不提供深淺正向證據，v3 只作活動衝突證據。v5 缺必要時間結構特徵時不可補零取得分期資格。當晚基準選至少 10 筆的最高相容版本，排除使用、guard、低覆蓋、無耦合及錄製邊界；不混版本。基準全幅變化 ≤max(0.0005 m/s², P50×25%) 視為低差異，回未判定，不因相對分位數低製造深眠。這個品質門檻仍未校準。

先判 `canStage`，再判 `canEnterDeep`／`canMaintainDeep`。Deep 需要 15 個連續合格分鐘、至少 80% quiet、最多 1 個 active 分鐘、窗口最多 6 個短動作事件、無長連續活動及當晚相對低活動；最後 5 分鐘也不能已持續高活動。入睡證據後 20 分鐘及手機使用後 15 分鐘保護阻擋 Deep，本身不代表 Awake。孤立短翻動可維持；3/5 active、密集動作或連續高 rolling RMS 退出，耦合／品質失效回未判定。回填最多 7 分鐘，不能跨使用、guard、缺口、特徵／錄製邊界。55% Deep 上限只是安全限制，裁掉的部分回未判定，沒有最低 Deep、固定比例或睡眠週期。

`motion.db` 非破壞性升至 schema 3；v1／v2 原列保留，新特徵為 SQL NULL，另保存 `capture_runs`。`sleep_events.db` 升至 schema 11 保存每筆 session 的 nullable `stageAlgorithmVersion`、`stageFeatureVersion`；舊結果無來源顯示舊版／來源不明。沿用全域規則版本 dirty/generation 觸發近期重算，不新建平行排程。沒有可相容的現存摘要時保留已保存階段，不把已清除來源的歷史洗成未判定；規則撤銷亦需窗口仍有 Sleep API 原始輸入。人工起訖不改，重算裁在人工界線內。同輸入／版本輸出確定，只有同步內容真正變更才遞增 revision，保留 clientRecordId；僅版本來源更新不重送。

### CSV 診斷閱讀

沿用首頁日期匯出。沒有 motion 的 session 分析分鐘也列出，量測欄位空白；不發起新的 UsageStats 查詢，也不觸發重算寫回。主時間線分段／本地統計／Health Connect 都來自 `sleepParts`；CSV 的 computed 表示當下離線推算，stored 表示已保存結果，不能混作同一版本結果。

- `reason_codes` 保存多個原因；`primary_reason` 按固定順序取主要原因，缺品質時不讓 onset guard 掩蓋缺資料。包括 NO_SLEEP_EVIDENCE、PHONE_IN_USE、ONSET_GUARD、MISSING_MOTION、INSUFFICIENT_COVERAGE、COUPLING_INSUFFICIENT／EXPIRED、BASELINE_INSUFFICIENT、LOW_SIGNAL_DIFFERENTIATION、WINDOW_TOO_SHORT、RECENT_WINDOW_INCOMPLETE、MINOR_GAP_BUDGET_EXCEEDED、ACTIVITY_TOO_HIGH、ENTER／MAINTAIN_DEEP、EXIT_SUSTAINED_ACTIVITY／COUPLING_LOST、SAFETY_CAP、LEGACY_FEATURE_LIMITATION。
- `can_stage`、`can_enter_deep`、`can_maintain_deep` 與 `prior_deep`、`current_eligibility`、entry／maintenance decision、formal action／stage、回填／safety-cap 標記、window blocker／interval、coupling_state／age／invalidation 可追到每個分析窗口；舊 `staging_event` 只補充轉換，不再是唯一原因。
- `phone_use_overlap_ms` 是精確重疊；`exact_computed_parts`／`exact_stored_parts` 以 `起點epoch ms:終點epoch ms:stage` 分號列出該分鐘內子區間。分鐘 computed_stage 不將幾秒使用擴大成整分鐘 Awake。
- `valid_motion_minute_percent` 是睡眠遮罩內符合 cadence／45 秒覆蓋的分鐘比例；`sensor_coverage_percent`／`span_motion_coverage` 改為整個分析跨度的感測覆蓋，分母包含已知清醒。`stageable_sleep_coverage` 是同一睡眠遮罩內可細分時間／睡眠時間。partial minute 與使用相交處的 sensor 覆蓋仍是按時間比例近似，不能還原未保存的逐秒覆蓋分布。
- `first_motion_delay_minutes` 是首筆現存 motion 摘要／事件的延遲；`first_valid_motion_delay_ms` 是第一次完成 15 分鐘連續品質窗口的時間，與入睡證據無關。無有效窗口輸出空白，不輸出 0。`night_longest_gap_ms`、`undetermined_reasons_ms` 彙總缺口及未判定主要原因時長。
- `session_span_ms`、`sleep_ms`、`deep_ms`、`light_ms`、`undetermined_ms`、`awake_ms` 是 computed 毫秒統計；版本分別列出 computed 及 stored 來源。舊摘要來源不足時 stored 的保留結果可與 computed 不同。
- capture_trigger、scheduled_window_start_ms、sensor_registered_at_ms、first_event_ms、requested_period_us、fifo_latency_us、fifo_count、wake_up、raw_events、rejected_events、mean_event_interval_ms、max_event_interval_ms 保存採集政策與事件間隔。註冊時及 5 分鐘摘要批次／結束 flush 保存，不做逐 callback 資料庫寫入。數值固定小數點，字串有 CSV escaping，不輸出帳號／App 使用清單或原始波形。

### 隔離採集實驗

Release 永遠忽略實驗設定；Debug 預設 OFF，沒有一般使用者必選 UI。僅 Debug manifest 包含 `CaptureExperimentReceiver`，有效值 OFF、EARLY_1HZ、EARLY_2HZ。用受控目標裝置執行：

```powershell
# A：正式 1 Hz／原啟動政策
adb.exe -s <序號> shell am broadcast -n com.rsps1008.sleeptrace/.motion.CaptureExperimentReceiver --es mode OFF
# B：只在睡眠窗內較早採集，仍 1 Hz
adb.exe -s <序號> shell am broadcast -n com.rsps1008.sleeptrace/.motion.CaptureExperimentReceiver --es mode EARLY_1HZ
# C：必要時才比較；只在睡眠窗內，明確要求 2 Hz
adb.exe -s <序號> shell am broadcast -n com.rsps1008.sleeptrace/.motion.CaptureExperimentReceiver --es mode EARLY_2HZ
```

命令只改 Debug 實驗政策，不自行啟動服務、不繞過暫停／低電量／權限／排程；既有服務重估，下一次合法啟動才採用。切回 OFF 會重新評估正式觸發。2 Hz 原始事件仍轉成 1 秒尺度摘要，所以本次只量測是否增加可用觀測，不宣稱能恢復全部秒內動作。接電不自動開實驗，無全天 FGS、持續 wake lock、新感測器或每秒輪詢。提前採集不代表提前入睡。

交替比較 A／B，必要才 C；同手機各多晚記錄採集延遲、有效與可分期覆蓋、缺口、主要阻擋原因、起訖電量和背景限制。未知的平板使用若在所有手機輸入上與睡眠相同，App 無法辨別；只保留可看見的手機使用及 API 起點保護，不能保證排除不可觀測情境。

### 本輪實際驗證及未完成範圍

2026-09-30 從 HEAD `0ee7451859569f62d805e81dd580f5b439a41260` 乾淨工作區整合。Java 21 執行：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease --no-configuration-cache
```

167 個 JVM 測試、0 failure／error；lint 25 warnings、0 errors；Debug／AndroidTest／unsigned Release APK 全部建置成功。沒有刪除重要既有測試；缺資料 LIGHT、跨缺口 entry、UNKNOWN 無記憶 grace 與平坦雜訊 Deep 的舊預期改為 v5 未判定／連續品質語意，另保留正向 Deep、翻身維持、活動退出、穩定 ID、時間守恆、版本邊界及 FIFO／取樣率 regression。新增 formal decision／maintenance 一致性、合法／超預算缺口、回填／safety-cap provenance 與 fixture bytes hash regression；SQLite instrumentation 案例已編譯但未執行。

只找到既有合成 `real_night_style.csv`，沒有真實整晚 raw CSV；沒有虛構實機那一晚結果。凍結 HEAD 的 algorithm 4 對照 algorithm 5（報告 `docs/staging-v5-replay.txt`）：329 分鐘合成跨度，Deep 132→104、Light 197→42、未判定 0→183、Awake 0→0；切換 8→11、<5 分鐘睡眠片段 1→1。有效分鐘覆蓋兩版均 89.6657%；新版跨度感測覆蓋 89.7568%，可細分睡眠覆蓋 44.3769%；未判定主要原因為缺 motion 33 分鐘、覆蓋不足 1 分鐘、耦合不足 149 分鐘。這是工程回歸，不是真實生理準確度、深眠比例優化或與原生／醫療演算法等價的證明。

目前只連接 Mi Note 10 與 Pixel 實體裝置，依測試的可丟棄模擬器限制未安裝、未跑會改資料／權限的 instrumentation。UI 實機目視、SQLite 升級裝置執行、Health Connect 真實寫入／刪除、採集實驗、Google 回報延遲、OEM／Doze／FIFO 完整性、PSG／穿戴對照與整夜耗電均尚未驗證。1 Hz 無法重建未觀測秒內動作；歷史摘要不能重建原始波形；所有耦合／分期門檻未校準，2 Hz 未比較準確度或耗電。Health Connect 成功亦不代表其他 App 已顯示。
