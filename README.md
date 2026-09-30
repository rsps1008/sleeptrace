# 眠迹 SleepTrace

Android 手機睡眠推估，包名 `com.rsps1008.sleeptrace`。Google Sleep API 提供睡眠起點證據，App 在已確認的睡眠 session 內再以手機使用紀錄與低頻動作估算淺眠／深眠；候選由 App 自動選擇並同步，不需要逐筆確認。分期僅供非醫療參考，未經 PSG 驗證。

App 圖示使用深靛藍夜色、淡紫月牙與藍綠睡眠軌跡，提供 Android adaptive、圓形、一般及 monochrome themed icon；原始生成圖與預覽保存在 `artwork/`。

## 自動記錄睡眠

1. 首次開啟先設定平日偵測時段；可選擇為週六、週日各使用同一組週末時段。跨午夜時依睡眠窗開始日套用，國定假日不會自動識別。每次開啟 App 都會自動檢查活動辨識、通知、Health Connect 與使用情況存取。可由 App 發起的權限會直接交由系統要求，使用情況存取則開啟 Android 系統設定頁。
2. 平日及可選的週末時段在同一個對話框內以 24 小時制拉選欄位設定開始／結束，會即時標示跨午夜狀態；不使用時鐘式選擇器。
3. 完成設定及必要授權後，App 在偵測時段內先等待 Google Sleep API 的高信心睡眠分類；達到工程門檻後才以 1 Hz 啟動加速度計。手機放床上或床邊均由 App 自行評估，不用選擇位置，也不用另外開啟動作偵測。
4. 前景服務任何情況都只在睡眠窗內執行；睡眠窗開始／結束由獨立鬧鐘接收器切換，睡眠窗外仍訂閱 Sleep API 區段事件。Android 12 以上允許「鬧鐘與提醒」後，鬧鐘可準時啟動邊界；若略過，App 仍設非精準鬧鐘並嘗試從睡眠分類回呼啟動，但 Android 可能拒絕或延遲背景服務，造成動作資料缺口。首頁可重新開啟特殊存取設定。前景通知只在睡眠窗服務執行時顯示，App 或通知可暫停整體記錄。開機、App 更新或程序回收只會在睡眠窗內嘗試恢復；若 Android 強制停止或 OEM 限制背景啟動，回到 App 後且當下位於睡眠窗才會補啟動。明確暫停後則保留暫停，直到按恢復。
5. 首頁只呈現睡眠紀錄、排程與必要連線狀態，不顯示感測器設定或動作時間軸；並顯示本機已保存的最後一筆 Sleep API 睡眠信心與回報時間，不會為此發起即時查詢，且該分數不是經過校準的準確率。睡眠紀錄的工程分數／理由可在自選詳情查看。
6. 完成 Health Connect 系統授權後，App 自動同步睡眠紀錄。睡眠詳情可檢視 Awake、Light、Deep 的工程推估；不辨識 REM，也不需要開啟 App 或按確認上傳。可選擇修正時間，儲存後會自動更新。

### 電力與硬體策略

首次完成時段與授權引導後，App 會檢查 Android 電池限制。未排除最佳化時會開啟系統允許背景執行的請求；若已被明確限制，則開啟 App 設定，請在電池選項選擇「不受限制／無限制」。返回 App 後重新讀取實際狀態。取消或未調整仍會繼續記錄，不會下次開啟又自動跳轉，首頁保留「允許整晚背景記錄」入口。

小米／Redmi／POCO 僅在首次流程一次性引導「自啟動／背景自啟動」設定。請允許眠迹，並將 App 電池策略設為「無限制」。部分 MIUI／HyperOS 不支援直達頁面時，App 會退回應用程式／一般設定，可搜尋「自啟動」；首頁不常駐顯示小米自啟動區塊。

以上設定需由使用者在系統介面操作，App 不會自行修改。解除限制不會提高取樣頻率或新增持續喚醒，仍採用下列省電策略；也不保證能避開全部廠商背景限制或強制停止。

| 情況 | 要求取樣頻率 | 要求硬體批次回報 |
| --- | --- | --- |
| 睡眠窗前 15 分鐘 | 不取樣；預熱 classify | 前景服務尚未啟動 |
| 睡眠窗內，Google 尚未判斷入睡 | 不取樣 | classify 訂閱及前景服務已啟動 |
| Google 睡眠信心值 ≥ 80、有 FIFO | 1 Hz | 使用硬體宣告 FIFO 容量的 80% |
| Google 睡眠信心值 ≥ 80、無 FIFO | 1 Hz | 不支援批次 |
| 未接電且電量 ≤ 15% | 暫停 | 接電或電量恢復後重新評估 |
| 窗外且距下一睡眠窗超過 15 分鐘 | 不取樣 | 僅訂閱 Sleep API 區段；前景服務停止；有特殊存取時設精準鬧鐘，否則用可能延遲的非精準鬧鐘 |

睡眠分類是 Google Play services 定期提供的推估，不是即時或確定的入睡事件；官方舉例可能約每 10 分鐘回報。睡眠窗前 15 分鐘至窗結束才訂閱分類，其餘時間只訂閱區段事件，以減少白天不需要的分類回呼。預熱 classify 不會啟動 FGS 或加速度計，但其中最近 20 分鐘、信心值至少 80 的分類可在睡眠窗開始時觸發取樣。原始分類、區段、每晚 UsageStats snapshot 與本機睡眠紀錄都在同一個 SQLite 資料庫中以交易保存；原始事件有時間索引與 14 天保留期，retention 最多每日檢查並清理一次。既有 SharedPreferences JSON 首次讀取後會遷移並移除，不再為每筆分類重寫完整 JSON。Sleep API 區段會先裁切到平日／週末對應睡眠窗；只在完整睡眠窗結束後查詢並保存該窗的 UsageStats snapshot，資料庫以 `(windowStart, windowEnd)` 複合鍵識別窗口，排程結束時間改變就會建立新快照。未結束的當晚不會凍結半窗資料或提早產生／同步候選。完整 snapshot 由 AutomaticPlacement、SleepAnalyzer 與 Health Connect 上傳共用，權限狀態與理由以每個睡眠窗分別判斷，因此來源／位置判斷、reason 與最後扣除的手機使用時間一致；App 偵測使用情況權限由無到有時會排入整理，以更新先前不可用的 snapshot。沒有使用情況存取權時，其他來源仍可推估，並保留無法排除手機使用的說明。若睡眠窗開始已過 2 小時仍無分類，且螢幕已持續關閉至少 2 小時，會啟動同樣 1 Hz 的低頻動作備援；它只避免整夜資料空窗，並不能證明使用者已靜止或入睡。這些門檻都是未校準的工程規則，可能延後啟動或整晚未觸發；缺少的前段動作資料不會補成安靜，最終仍可使用 Sleep API 區段及手機使用紀錄推估。

### 非醫療淺眠／深眠推估

分期只在已成立的 SleepSession 範圍內離線重算，結果以合併區間保存。Google Sleep API 用於確認睡眠 session／起點；UsageStats 用於清醒與睡前操作證據；固定秒尺度 motion、當晚相對活動及 temporal smoothing／hysteresis 用於 Light／Deep 工程推估。手機靜止本身不代表入睡，沒有 motion 的部分預設 Light，不會憑空補 Deep；不辨識 REM，未經 PSG 驗證。單筆詳情保留「依手機活動與 Google Sleep API 推估，非醫療睡眠分期」。

動作摘要的 `featureVersion 1` 是舊版依 callback 間隔計算的特徵，`featureVersion 2` 是第一版 epoch-second bucket 正規化特徵；兩者都保留供歷史／相容用途，不會與新版 RMS percentile、rolling median 或 Deep staging 混算，且沒有原始加速度波形可供回算。`featureVersion 3` 表示硬體最小取樣間隔超過 1.2 秒的 cadence 不相容摘要，只保留活動摘要，不參與 Deep staging。`featureVersion 4` 是目前以 SensorEvent timestamp cadence anchor 產生的約 1 秒固定特徵，可供新版 Light／Deep 推估。Resampler 容忍一般 callback jitter；缺少事件不插值。只有 v4 BED／QUIET 可累積 motion-only 安靜睡眠候選；v3 BED／ACTIVE 可作為動作衝突證據，但 v3 QUIET、v1／v2 的任何分鐘都不能建立或延長候選，也不參與衝突分數。Storage priority 為 v4 > v3 > v2 > v1。暫停新的自動記錄不會阻止既有資料完成 staging rule migration；目前 staging algorithm version 為 3。

- **Sensor normalization**：仍要求約 1 Hz，實際 callback 可高於要求值。App 以第一筆 SensorEvent timestamp 作 cadence anchor，後續依固定的硬體支援 cadence 接受最多一筆代表 sample；約 100 ms 的早到／晚到 jitter 會對齊到 cadence slot，不依賴 epoch 整秒邊界。FIFO burst 仍只看 event timestamp；重複／倒序／非有限事件忽略，跨過的 slot 保持缺資料且不插值。一般 cadence 的新資料 `sampleCount` 約 60／分鐘；超過 1.2 秒的硬體最低間隔仍保留 activity／coverage，但以不相容 feature version 排除於新版 Deep percentile。沒有提高 sensor Hz、FGS 時間、wake lock 或每約 5 分鐘 SQLite 保存頻率。
- **Nightly baseline**：只取 session 內、排程內、覆蓋至少 45 秒、非手機使用／onset guard 且有 BED 證據的有效 motion；至少 10 分鐘。一次排序計算 P25／P35／P50／P65／P70／P75，不永久存入 DB。UNKNOWN 不單獨建立 baseline 或 Deep；Google confidence／motion／ambient light 不直接決定 Deep。
- **Deep ENTER**：真正包含目前分鐘與前 14 分鐘的窗口；允許 12～15 個 valid motion 分鐘，QUIET 至少 80%，ACTIVE 最多 1 分鐘，BED 至少 10 分鐘（分布過窄時 12），rolling median RMS ≤ 當晚 P50，目前分鐘本身仍須是有效 v4 BED／QUIET。手機使用、15 分鐘 onset guard、before-evidence、排程外、BEDSIDE 與窗口內出現非 v4 feature 都是 hard block；單純 missing／coverage 不足只減少 valid count，由 12 分鐘下限判斷。從 session 起點與最早 Sleep API sleep 證據中較晚者起，至少 20 分鐘後才可進 Deep。確認後最多回填 7 個連續、有效、BED／QUIET 且 RMS ≤ P70 的穩定分鐘，回填遇到缺口、版本邊界、guard、長活動、BEDSIDE 或最早允許 Deep 時即停止。
- **Deep STAY／EXIT**：維持時不再逐分鐘要求 RMS ≤ P35。1～2 分鐘翻身或安靜但稍高 RMS 可以保留；5 分鐘內至少 3 分鐘 ACTIVE 時退出並將該活動橋接段改回 Light。完整 5 分鐘 rolling median > P70 且連續 3 個窗口時退出。手機使用直接 Awake 並 reset，guard、缺口／低覆蓋與 BEDSIDE 直接回到 Light。有效、QUIET、RMS ≤ P75 的 UNKNOWN 最多連續維持 5 分鐘，第 6 分鐘回到 Light；UNKNOWN 不單獨進 Deep。
- **睡前手機使用**：每個已結束睡眠窗的 UsageStats snapshot 額外保存窗前 30 分鐘證據，同樣維持 `(windowStart, windowEnd)` key。staging 傳入 session 前 30 分鐘至結束的使用區間，Awake 仍只裁切計算 session 內部分。最後一次手機使用後 15 分鐘內不得 Deep；較晚的 Sleep API 起點也不能被早先手機靜置回填。
- **Safety bounds**：P75−P25 ≤ max(0.0001 m/s², P50×15%) 視為訊號辨識力不足；提高 BED entry 要求並將 Deep 上限降低到 session 非 Awake 時間的 35%，一般上限為 55%。只在超過上限時，依 rolling median 較高的 Deep 段優先收回；這是防止演算法把整晚判 Deep 的安全界線，不強迫任何固定生理比例。
- **舊資料**：`motion.db` schema 2 增加 `featureVersion`，v1 是舊 callback-dependent、v2 是舊 epoch-second bucket、v3 是 cadence 不相容、v4 是目前 cadence-anchor 約 1 秒特徵。各版本摘要均保留；同一分鐘不同 feature definition 不相加，只有 v4 參與新版 Deep baseline／rolling staging。Storage priority 為 v4 > v3 > v2 > v1，版本順位也決定衝突分鐘保留哪種摘要。已存摘要沒有原始波形，因此無法把 v1/v2 事後還原成 v4。`sleep_events.db` schema 10 保存 UsageStats `evidenceStart`，舊 snapshot 保留窗口 key，缺少前置證據者在完整窗口下一次整理時補查一次；percentiles 不保存。stage 變更以相同 session ID／clientRecordId 遞增 revision，已同步或正在同步的手動紀錄回到 PENDING，舊同步結果不可覆蓋新版本；手動修正的 start／end 不被自動分析改寫。

這是工程上的相對穩定程度估算，不是醫療用途、不是生理睡眠分期，也沒有 PSG 驗證；不辨識 REM。手機位於床邊、震動或床墊差異會讓動作推估更有限。時間軸與淺眠／深眠／清醒摘要只出現在單筆詳情，不放到首頁。

資料庫使用 SQLite WAL；歷史 session 的同步狀態、版本與時間修正都以 id 做單筆 upsert，不會因單筆狀態變更清空並重建整張 `sessions` 表。reconcile 只讀取最近 48 小時重疊的 session，加上仍為 PENDING／SYNCING／FAILED_RETRYABLE 的舊 session；永久同步失敗不會把掃描範圍拉回數月前。時間修正、手動重試、權限恢復及新事件會更新 work generation／dirty flag，讓 `KEEP` 工作在執行期間發現新變更後重新整理。合併後只在同一個 transaction 內刪除被取代的未同步列、upsert 新增／版本／退休列，未變更歷史不會重寫。`replaceSessions` 僅保留給明確的完整重算／遷移用途。`sessions` 依開始時間與同步狀態建索引，支援 limit／offset 及只讀摘要欄位；首頁只讀最新 5 筆（最近睡眠加最多 4 筆歷史），不解析清醒區間 JSON。歷史紀錄用 RecyclerView 分頁載入，追加頁面只通知插入範圍，選取後才按 ID 讀取該筆詳情與清醒區間。首頁只查詢最新一筆 classification，前景服務只查詢最近 20 分鐘的樣本；`MotionStore` 由 `SleepTraceApplication` 共用，避免服務與背景整理各自持有 SQLite helper。首頁骨架在 Activity 建立時建立一次，資料刷新只更新既有 View 的文字、Badge 與 visibility。

睡眠窗內的背景服務監聽 `ACTION_BATTERY_LOW`／`ACTION_BATTERY_OKAY` 及接／斷電事件；精確電量在配置需要時以一次性的 `ACTION_BATTERY_CHANGED` 快照取得，不因每 1% 電量變化持續喚醒。螢幕 ON／OFF 只更新記憶體中的狀態，不重查排程、SQLite 或電量。未改變的下一個睡眠窗邊界不會重設鬧鐘。背景整理對 Sleep API segment 使用時間範圍查詢、分類只取最近 48 小時；未完成同步的舊 session 仍會擴大 segment 起點以保留匹配能力。`MotionStore.append` 會在單一交易內先讀出批次涵蓋範圍的既有分鐘，避免逐筆建立 Cursor。立即 reconcile 使用 WorkManager `KEEP` 合併重複觸發；持久 generation 也追蹤 session 編輯／重試及 Health Connect、UsageStats 權限恢復，Worker 若執行期間收到新變更會再整理。每日 24 小時 recovery 有 6 小時 flex 並要求電量非低。首頁只在有待整理／待同步資料時安排立即 reconcile。首頁的 DataStore、Health Connect 權限與 Android 背景狀態讀取也由 `HomeViewModel` 的 I/O 工作收集後一次更新畫面。

Health Connect 待同步 session 會先驗證、保存 `SYNCING` 狀態，再以多筆 `SleepSessionRecord` 批次寫入；每個請求最多 1,000 筆，較大的佇列切成多批。暫態 batch failure 將該批標成 `FAILED_RETRYABLE` 並停止；永久 batch failure 會逐筆 fallback，成功的紀錄照常同步，只有單筆仍被拒絕才標成 `FAILED_PERMANENT`。永久失敗不會被每日 recovery 再次提交；使用者可從詳情手動重試，取得 Health Connect 權限時也會重新排入。舊版 `FAILED` 會遷移為可重試狀態。穩定 client ID／revision 保留供安全重試。分期區間與 session 一起保存；舊紀錄保留 `SLEEPING` 相容 fallback，新紀錄寫入 AWAKE／LIGHT／DEEP。Health Connect 1.1.0 的 `SleepSessionRecord` 支援 `STAGE_TYPE_AWAKE`、`STAGE_TYPE_LIGHT` 與 `STAGE_TYPE_DEEP`，本 App 直接使用這些 API 常數，沒有 hardcode 數值或升級依賴。時間軸顏色取自主題 primary、secondary、error 與次要文字色，Android 12 以上可套用系統動態色彩。自動放置與分期門檻尚未跨機型／床墊校準，不能視為精度提升證明。

依感測器最小取樣間隔調整實際請求；批次延遲以 FIFO 容量 × 取樣間隔 × 80% 換算成 Android API 要求的微秒值，不另設 App 時間上限。若換算結果超過 API `Int` 可表示範圍，才限制為 `Int.MAX_VALUE`。以上是要求值，Android／硬體可能提前回報或以不同頻率取樣。優先使用帶 FIFO 的 wake-up accelerometer；非 wake-up 或無 FIFO 的感測器在 CPU 休眠時可能漏資料，內部保留診斷狀態，不要求使用者處理。接電時也維持 1 Hz，不會提高取樣頻率。

不持有持續 CPU wake lock，不開陀螺儀、麥克風、定位或相機。睡眠窗限定 FGS 不會因缺少特殊存取而退回全天常駐。Android 12+ 允許「鬧鐘與提醒」時使用 exact alarm 喚醒邊界接收器；未允許時使用非精準鬧鐘並盡力啟動，但系統可能拒絕／延遲背景 FGS，進而漏掉動作資料。事件本身仍會檢查平日／週末睡眠窗。批次以 SensorEvent 的單調時鐘時間轉換成資料時間，不使用整批送達時刻。

每分鐘累積三軸變化的 RMS、活動持續時間、有效覆蓋時間及樣本數。每約 5 分鐘以 SQLite 交易保存摘要；節流依 HandlerThread 實際處理的 `elapsedRealtime()` 計算，且包含裝置深度休眠時間，因此硬體 FIFO 一次釋放跨多分鐘的樣本時，不會在同一批事件中連續開啟多次交易。停止、暫停或切換模式時會先要求 flush，最多等待 2 秒，再保存已收到資料。系統直接殺死程序可能遺失最後約 5 分鐘尚未儲存的摘要及未送達批次，這些缺口不補成安靜。保留 14 天以上的動作摘要會在新增資料時至多每日清理一次，不保存原始波形，並排除系統備份。

首頁「動作資料匯出」可選擇本機日期，透過 Android 文件建立器匯出該日 CSV。除原始分鐘摘要外，CSV 提供 row-level `feature_version`、resampled sample count、placement／level、session、nightly P25/P35/P50/P65/P70/P75、rolling median、computed/stored stage、staging role/reason、baseline 版本／筆數／原因、每個 session 的 `legacy_feature_minutes`、`current_feature_minutes`、`cadence_incompatible_minutes`、BED/UNKNOWN 分鐘數、Deep enter/exit 次數，以及 `valid_motion_minute_percent` 和 `sensor_coverage_percent`。各 session 統計先彙總一次，再附加到該 session 的分鐘列。前者代表非 Awake 時間中具有效 current-feature 分鐘的比例；後者才依 `coveredMillis` 累計感測覆蓋秒數。因分鐘摘要沒有每秒分布，首尾 partial minute 與 Awake 相交處按時間比例估算，故 sensor coverage 是 diagnostic approximation。`staging_motion_role` 區分 `full`、`stay_only`、`activity_only`、`excluded`；`staging_motion_usable` 為 true 時 exclusion reason 留白。computed_stage 是目前規則離線重算值，stored_stage 是已保存分期；匯出不修改分期或觸發新的 UsageStats 查詢，診斷只使用現存 snapshot／事件。逾 14 天或未落盤摘要可能缺失；CSV 不含原始波形。


### 試驗規則

- 每分鐘有效覆蓋至少 45 秒才判讀；長間隔、倒序、重複或非有限值樣本不增加覆蓋。
- 相鄰樣本三軸差值 ≥ 0.15 m/s² 算活動；活動時間比例 ≥ 5% 或變化 RMS ≥ 0.20 m/s²，標示該分鐘有動作。這些是可調的工程起點，未以 PSG 校準。
- 只有 v4 current cadence-anchor 的 BED／QUIET 可以累積 20 分鐘安靜證據；v3 activity-only 的 BED／ACTIVE 可用來結束區段或降低既有候選的參考分數，v3 QUIET 與 v1／v2 資料不建立候選。完整區段至少 30 分鐘，才成為備援候選。手機使用、缺失／低覆蓋資料或持續活動 5 分鐘會切斷區段；短暫翻動不等於清醒。同一時段的多個合格安靜段會分別保存，避免分段睡眠只留下最長一段。
- 若床上動作有效資料至少 30 分鐘且涵蓋候選一半以上，而其中活動分鐘占比 ≥ 30%，Sleep API 候選分數降低 30 分。重疊來源依調整後的參考分數自動選擇，平手優先 Sleep API；低分不阻擋同步。
- 舊版需要人工處理的紀錄會自動轉入同步佇列。完全沒有候選、有效睡眠不足 30 分鐘或舊紀錄缺少已扣除手機使用的時間明細，App 會自動略過，不要求人工裁決。尚未授予使用情況存取權時，App 使用其餘資料推估並保留限制說明。
- 新候選若跨越多筆破碎歷史紀錄，會保留其中一筆穩定 ID 作為新版；其餘已同步的 ID 先從 Health Connect 移除，成功後才送出新版，避免因保守跳過而長期不更新或留下重複資料。
- 手機使用區段在本機扣除，並以 Health Connect 的 AWAKE 階段寫入；其餘新紀錄依推估寫入 LIGHT／DEEP，舊紀錄無分期時保留 SLEEPING。
- 首頁不放感測診斷圖；單筆睡眠詳情顯示 Awake／Light／Deep 時間軸與非醫療用途說明，顏色沿用主題及動態色彩。
- 睡眠詳情與時間修正對話框由 `SleepDialogHelper` 集中管理；時間修正同一頁同時選擇入睡／醒來時間並即時計算總時長。歷史卡片超過摘要上限時可用「查看全部紀錄」開啟可滾動清單。
- 保留紀錄 ID 與歷史。睡眠起訖／清醒時間變動時增加 `clientRecordVersion` 並更新同一筆；內容相同不重傳，手動修正不被自動推估覆蓋。同步進行中若資料改版，舊請求不能覆寫新版。
- 同步暫時失敗時由 WorkManager 自動重試，採 10 分鐘起的指數退避；系統可能延後背景執行。永久性錯誤回傳 failure，不再無限喚醒裝置；程序中斷後會以相同 ID／版本恢復。缺少 Health Connect 授權時保留紀錄，授權後或下次 App 開啟、定期工作時自動繼續。Android 的系統授權不能由 App 自行同意。
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
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest assembleRelease --no-configuration-cache
```

`SleepScheduleTest` 涵蓋平日／週末時段與跨午夜邊界；`SleepUsageSnapshotTest` 檢查手機使用區間、理由文字、每窗權限狀態及窗口結束時間鍵；`AutomaticSyncTest` 覆蓋永久錯誤不會自動重試、批次永久失敗逐筆隔離，以及暫態錯誤保持整批可重試。`MotionEngineTest` 涵蓋 Google 分類觸發門檻、1 Hz／FIFO 設定、批次時間、資料缺口、手機使用、床邊放置、跨午夜、候選分段、動作衝突及同步 ID 保留。`MotionRuntimeTest` **僅供可丟棄的模擬器**：會改測試 App 時段、授權並模擬分類與電池狀態，檢查等待分類、觸發取樣、摘要保存、低電量暫停與供電恢復。不要對日常使用的實機執行該測試。

2026-09-29 本輪資料庫／UI／同步效能更新已通過 43 個 JVM 測試、Lint（無 issue）、Debug APK 及 Android 測試 APK 建置；未在裝置執行會改動資料或權限的 instrumentation test。已涵蓋單筆 session 更新、最新／近期 classification 查詢、WAL 共用資料庫、固定首頁骨架、DST 跨日計算、零配置動作差值、校時補償及 Worker 暫態／永久錯誤分流的程式實作。真實 Health Connect 寫入／刪除、Google 分類延遲、Doze／OEM 背景行為、FIFO 與整夜耗電仍需在可丟棄模擬器或受控實機另外驗證。

2026-09-30 動作摘要匯出：首頁新增日期選擇及 Android 文件建立器 CSV 匯出，輸出本地分鐘時間、覆蓋秒數、活動秒數、合併三軸變化 RMS、樣本數與放置模式；`testDebugUnitTest` 70 項通過。未在實機確認昨晚資料是否完整，也未保存或匯出逐軸原始波形。

2026-09-29 P0／P1／P2 省電與排程更新：完整睡眠窗結束後才擷取每晚 UsageStats snapshot，共用於 AutomaticPlacement、SleepAnalyzer 與 Health Connect；未完成窗口不凍結半窗 snapshot、不產生或同步候選；App 偵測 UsageStats 權限由無到有時排入整理更新舊 snapshot；Sleep API 分類改為睡眠窗前 15 分鐘至結束，窗外只訂閱 segment；同步永久失敗不再被 recovery 重送；睡眠窗邊界避免無變化重設；立即整理採 KEEP，每日 recovery 有 BatteryNotLow；資料 retention 每日最多清理一次；Release R8 啟用；新增平日／週末排程。FGS 任何情況都只於睡眠窗執行；未授予鬧鐘特殊存取時使用非精準鬧鐘並提示背景啟動可能漏記，無全天 FGS fallback。53 個 JVM 測試通過；Debug lint 30 條 Warning、無 Error／Fatal；Debug APK、Android 測試 APK 與啟用 R8 的 unsigned Release APK 建置成功。未在實機驗證鬧鐘權限、背景啟動、Health Connect 或整夜耗電。

2026-09-29 背景與 UI 查詢優化：完成低電量事件監聽、SleepReconciler 時間範圍查詢、SleepStore 主鍵修正、MotionStore 批次既有資料查詢、HomeViewModel I/O 卸載、時間條圓角裁切、單一時間修正對話框及完整歷史清單入口。`testDebugUnitTest`、`lintDebug`、`assembleDebug`、`assembleDebugAndroidTest` 均通過；未在裝置執行會修改資料／權限的 instrumentation test，真實電量喚醒次數、OEM 背景行為與整夜耗電仍未量測。

2026-09-29 session 歷史與同步擴展性更新：首頁改查最新 5 筆摘要並延後載入清醒明細；完整歷史使用 RecyclerView 分頁；session 加入排序／狀態索引；Health Connect 改批次寫入（最多 1,000 筆／批）；自動放置加入局部底噪相對門檻、邊界鬧鐘加安全回退、時間軸套用主題與動態色彩。43 個 JVM 測試通過、Lint 零 issue、Debug APK 與 Android 測試 APK 建置成功。未執行裝置 instrumentation／實際 Health Connect 寫入或 UI 主題目視驗證；底噪門檻仍未以不同手機與床墊校準，深淺眠分期未實作。

2026-09-29 本輪資料庫／刷新／系統邊界效能更新：reconcile 改為最近 48 小時加未完成同步 session 的範圍查詢，差異列在單一 transaction 內刪除／upsert；WorkManager 只在完成狀態刷新，`HomeViewModel` 以 conflated queue 防止並行讀取；`UsageMonitor` 遇到裝置關機／啟動會結算並清除跨 boot 狀態；歷史 RecyclerView 改用精確的 footer／range 通知；FIFO 批次存檔改以 HandlerThread `elapsedRealtime()` 節流，包含裝置深度休眠時間。43 個 JVM 測試通過、Lint task 成功（保留既有 warnings）、Debug APK 與 Android 測試 APK 建置成功。未執行裝置 instrumentation、Health Connect 實際寫入／刪除、UsageStats 真機重啟事件、FIFO 長批次與整夜耗電驗證。

最新版也已將睡眠 session、Sleep API segment 與分類統一到 `sleep_events.db`，首頁資料由 `HomeViewModel` 載入。舊 JSON 的實際升級遷移測試已編譯，但本機連接的裝置都是實機，沒有執行會改動裝置資料的測試。

首頁仍使用既有 Material View；Activity 建立時一次建立標題、睡眠、排程、權限及背景設定卡片，WorkManager 發出狀態變更時只更新既有 View，不再清空並重建整棵 View 樹。

首頁的資料讀取、權限／背景狀態彙整已由 `HomeViewModel` 管理；Activity 只觀察狀態並繪製 Material View。WorkManager 只有在工作資訊出現完成狀態時才要求刷新，ViewModel 以 conflated request queue 合併短時間重複請求，避免並行重讀 SQLite、DataStore 與權限狀態。

`SleepTraceApplication` 提供 application-scoped 依賴容器，讓 Activity、ViewModel、Worker、Receiver 與前景服務共用設定、資料庫與同步元件，不再在各入口重複建立它們。

2026-09-29 同步可靠性更新：Health Connect 永久 batch failure 改逐筆 fallback，只隔離單筆失敗紀錄；`FAILED_PERMANENT` 不再擴大 reconcile 舊資料範圍；UsageStats snapshot 改用 `(windowStart, windowEnd)` 複合鍵並按每晚權限狀態分析；時間修正、手動重試與權限恢復納入 work generation。完整 `testDebugUnitTest` 59 項通過，`lintDebug`、Debug APK、Android 測試 APK、Release APK 建置成功；未執行會修改裝置資料／權限的 instrumentation test，Health Connect 真實寫入與 SQLite 舊版升級尚未做裝置端驗證。

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

2026-09-30 非醫療 Light／Deep 推估：新增 `SleepStageEstimator`，只重算已成立 session 內的合併階段區間；motion-only 靜止候選須由 Sleep API segment 或 confidence ≥ 80 classification 確認起點，手機使用標記 Awake 並重置 continuity，15 分鐘 guard 後以當晚 P35、≥10／15 分鐘低活動窗及短翻身平滑判 Deep。新資料寫入 Health Connect AWAKE／LIGHT／DEEP，舊資料保留 SLEEPING fallback；`sleep_events.db` 升至 schema 9 保存 stageIntervals。詳情新增階段摘要與時間軸，沒有新增夜間感測，仍為 1 Hz。70 個 JVM 測試通過，lintDebug 有 24 條 Warning、無 Error，assembleDebug 成功；裝置 SQLite migration、真實 Health Connect 寫入、PSG／穿戴對照與整夜耗電尚未驗證。Stage constants 依 [Health Connect `SleepSessionRecord` API](https://developer.android.com/reference/androidx/health/connect/client/records/SleepSessionRecord)。


2026-09-30 第二輪校正驗證：testDebugUnitTest 共 89 項通過、0 failure／error；lintDebug 25 條 Warning、0 Error；assembleDebug、assembleDebugAndroidTest、assembleRelease 全部成功，Release 仍為 unsigned APK。合成 real_night_style fixture 的凍結舊規則產生 3 分鐘 Deep，新規則產生 132 分鐘，僅作工程 regression，不能視為真實生理分期。未在裝置執行 StagingStorageRuntimeTest、實際 Health Connect 寫入、實機整夜耗電或 PSG／穿戴對照。

2026-09-30 motion feature guard／storage priority／reconcile migration：MotionSleepEstimator 只以 v4 BED／QUIET 建立或延長安靜候選，v3 BED／ACTIVE 保留衝突證據；v3 QUIET 與 v1/v2 不提供睡眠正向證據。儲存順位明確為 v4 > v3 > v2 > v1。候選生成規則改變使 reconciliation rule version 升至 4，將觸發已標記版本 3 的裝置整理近期資料。完整 `testDebugUnitTest` 113 項通過，lintDebug 25 warnings／0 errors，Debug、AndroidTest APK 與 Release 建置成功；隔離 SQLite 寫入與規則遷移 instrumentation 僅編譯，未在裝置執行，因目前只有實體裝置且測試限用可丟棄模擬器。

2026-09-30 規則遷移撤銷：升版整理時重新驗證近期已完成睡眠窗內的自動 session；新版不再產生者保留本機列，未同步列標為 `SKIPPED`，已同步或可能已送出的列標為 `RETIRED` 並交由既有 Health Connect 刪除流程；手動修正及仍匹配新版候選者保留。117 個 JVM 測試通過，lintDebug 25 warnings／0 errors，Debug、AndroidTest APK 與 Release 建置成功；SQLite tombstone／generation instrumentation 已編譯但未在只有實體裝置的環境執行，Health Connect 實際刪除亦未連服務驗證。

reconciliation rule version 目前為 4，沿用 `SleepStageEstimator.ALGORITHM_VERSION` 作為已保存規則版本。`AutomaticWorkSignals` 在保存版本低於目前版本時視為 dirty，讓升級後沿用既有 KEEP 工作重算最近 48 小時；規則遷移時也會重新驗證完整結束睡眠窗內、未手動修正的自動 session。新版不再產生且未同步的列保留在本機並標成 `SKIPPED`；已同步或可能已送出的列保留為 `RETIRED`，由既有 Health Connect `clientRecordId` 刪除流程處理，成功刪除後才改為 `SKIPPED`。手動修正與新版仍產生的候選不撤銷；不會為此直接刪除本機 session。Health Connect 的退休刪除與新增寫入共用程序內互斥，避免 immediate／periodic worker 交錯而讓舊請求在刪除後重新寫入。只有 reconcile 資料交易完成且 generation 仍相同時才記錄新版本。第二輪分期版本曾從 2 升至 3；後續動作候選 feature-version 規則改變候選邊界，因此再升至 4。這不增加感測時間或分鐘摘要保存頻率。
