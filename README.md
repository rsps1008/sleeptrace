# 眠迹 SleepTrace

2026-10-03 同晚參考圖回放：新增可處理「有動作摘要、沒有 session」匯出的隔離測試，比較 1,586 組固定／局部門檻、逐段時間重疊及相鄰門檻敏感性，並保留前一晚回歸與反例控制。實驗參數尚未套用正式 App，規則仍為 12；沒有原始 Google 分類／精確使用區間時，只能驗證固定窗口內的條件分期，不能宣稱自動入睡／起床已重現。個人資料及圖表只保存在忽略的建置目錄。重現方式見 [同晚回放工具](tools/offline-replay/README.md#同晚參考圖與沒有-session-的匯出)。

2026-10-03 規則 12 修復：動作候選的 Sleep API 起點確認允許使用候選開始前最多 2 小時內的最新分類，處理 Google 已先確認睡眠、但動作耦合稍後才累積成 BED 的情況；若期間已有較新的非高信心分類，舊睡眠證據不再採用。候選已同時具備 Google 證據及 BED 耦合後，可跨越同一錄製中暫時失去耦合但品質有效的安靜分鐘，連續 3 個每分鐘至少 30 秒活動的密集動作則從首分鐘結束睡眠。純靜止、超過 2 小時的舊分類仍不能建立睡眠。升版會透過既有 dirty/generation 遷移重算近期完整窗口，包括已被規則 11 處理過但未產生 session 的資料。

Android 手機睡眠推估，包名 `com.rsps1008.sleeptrace`。Google Sleep API 提供睡眠起點證據，App 在已確認的睡眠 session 內再以手機使用紀錄與事件時間正規化的動作特徵估算淺眠／深眠；候選由 App 自動選擇並同步，不需要逐筆確認。分期僅供非醫療參考，未經 PSG 驗證。

App 圖示使用深靛藍夜色、淡紫月牙與藍綠睡眠軌跡，提供 Android adaptive、圓形、一般及 monochrome themed icon；原始生成圖與預覽保存在 `artwork/`。

## 真實夜間門檻離線比較（2026-10-02）

第二輪依兩組偏好的分段形態，再完成 **1,268 組不同門檻**；C 為 115 分鐘／8 段、D 為 117 分鐘／7 段（最短 6 分鐘）、F 為 109 分鐘／8 段。最長段均為 39–40 分鐘，前半夜分段較前輪 101 分鐘／8 段方案多。完整門檻、分段圖、退出原因與相鄰門檻敏感性見 [第二輪回放報告](docs/offline-replay-2026-10-02-round2/README.md)。C 有三段剛好達 5 分鐘下限，部分段落由耦合支持到期截斷；形態偏好不代表生理準確度。44 項相關 JVM 測試通過，前輪 A／B 完整起訖重現；正式演算法仍為規則 11。

今晚 CSV 已完成 383 次回放（362 組不同門檻），現行規則逐段重現 Deep 31 分鐘／1 段。單純把入口窗口 15→10 分鐘只變為 33 分鐘／1 段；「建立支持需 3→2 個動作、入口 RMS 上限 P50→P65」得到 88 分鐘／4 段，是待跨夜驗證的候選。小米舊圖只作形態參考，沒有輸入演算法、固定比例或補滿深睡；可調出 8 段的敏感設定仍有過長段或碎裂問題。

比較圖、全部設定、限制及 44 項相關 JVM 測試結果見 [離線回放報告](docs/offline-replay-2026-10-02/README.md)，重現命令見 [離線工具](tools/offline-replay/README.md)。實驗副本僅以 opt-in Gradle init script 加入 JVM 測試；**正式演算法、App 版本、取樣與同步均未改動，仍為規則 11**。

## 首頁閱讀與感測資訊（2026-10-02）

首頁依序呈現最近睡眠、記錄與排程、先前紀錄、連線與權限、資料與匯出。睡眠時長以主卡呈現；入睡／起床、平日／週末分行，長說明與操作按鈕分開。窄螢幕或放大字體時操作按鈕改為上下排列，採集欄位依可用寬度切換排列，並支援淺色／深色配色。保留暫停／恢復、修改時段、修正時間、詳情、完整歷史、授權與 CSV 匯出。

「資料與匯出」將數字分成三列：**App 要求頻率**是政策目標、**原始事件實測**依已保存的事件時間間隔計算、**特徵正規化上限**是 App 處理上限，不是實測特徵率。感測器能力使 Android 註冊要求不同於政策時，另列註冊要求。採集時間明示為最近一次採集開始，並非即時記錄狀態；無資料不顯示假造的零值或 10 Hz。

點「展開硬體與批次說明」可查看：

- **硬體 FIFO 容量**：Android 從所選感測器回報的最大／保留事件筆數，不是 App 設定的容量，也不是目前使用量。最大容量可能共用；保留量為零不等於沒有 FIFO，舊紀錄缺欄位會標示未保存。
- **App 批次等待上限**：App 依硬體容量與可能事件間隔換算後，取 80% 作為等待要求，預留 20% 緩衝；優先使用保留容量，沒有保留量才參考最大容量。App 未另設固定秒數上限，仍受 Android 可接受範圍限制。畫面顯示保存的要求值並保留小數秒，不是實際回報／CPU 喚醒間隔。
- **感測器喚醒類型**：來自採集時保存的硬體回報，不代表已驗證整夜完整性或耗電。

硬體欄位定義可對照 [Android Sensor 文件](https://developer.android.com/reference/android/hardware/Sensor#getFifoMaxEventCount())；批次要求定義見 [SensorManager 文件](https://developer.android.com/reference/android/hardware/SensorManager#registerListener(android.hardware.SensorEventListener,%20android.hardware.Sensor,%20int,%20int))。首頁固定骨架刷新，保留展開狀態及捲動位置；完整歷史入口修正為有較早紀錄即可使用。這次未修改取樣政策、分期規則、同步流程或資料庫 schema。

JDK 21 驗證：完整 266 項 JVM tests 通過、Debug 與 AndroidTest APK 建置成功、Lint 23 warnings／0 errors。一次性 Android 16 emulator 已驗證首頁淺／深色 360dp 及深色 320dp／font scale 2.0，包含文字不裁切、按鈕可達、歷史入口、展開收合及採集刷新保留位置；服務／CSV／詳情整合亦通過。報告與合成 UI 截圖保存在 `app/build/reports/home-ui/`。未操作實體手機；測試畫面與顯示的已同步狀態是 UI fixture，不代表實際睡眠或 Health Connect 端到端驗證。

## 1.0.1 夜間測試版：規則 11（2026-10-01）

本輪從 `272f743` 審查取樣、動態觀測、AUTO 耦合、睡眠候選、分期、保存及同步，並修正下列問題。App 仍為 `versionCode=2`／1.0.1、feature v7／10 Hz、DB schema motion 4／sleep 11。

- **末段起床少等一筆回報**：低分串從原排程結束前一小時內或延長期間開始時，改為連續兩筆低分、至少 10 分鐘；更早仍需三筆、至少 20 分鐘。睡眠前證據、回報新鮮度與缺口限制保留。舊缺口或前半夜低分不再卡住之後重新建立的有效起床證據。
- **減少背景重複計算**：感測回呼共用已解析窗口，窗口／時區更新即失效；8 小時、10 Hz 的受控測試從逐筆 288,000 次日曆查詢降為 1 次（沒有途中設定更新的情況）。v7 分期跳過不會採用的 legacy P70 支持點掃描；沒有降低採樣率或放寬 Deep 門檻。
- **窗口與訂閱更新保持順序**：服務設定查詢串行並合併重複請求，失敗後可由下次事件重試。Sleep API 訂閱／取消也串行且只保留最新目標，避免晚到舊回應污染暫停／恢復狀態。開機、時間／時區或精準鬧鐘權限變動後重新設定邊界鬧鐘；窗外 sensor callback 也會要求一次窗口重估，補強邊界廣播遲到的情況，不增加輪詢或 wake lock。
- **候選不能跨錄製／版本邊界拼接**：錄製重啟、feature 切換或資料缺口會結束原安靜段。規則升至 11，透過既有近期重算／撤銷流程保留穩定 ID、人工修正與退休紀錄。已持久化 closed 的觀測窗保持閉合，不回溯重開。
- **暫態同步失敗停止密集呼叫**：批次永久失敗改逐筆嘗試時，一旦遇到暫態錯誤，剩餘列保留相同 ID／revision、等待既有 WorkManager 退避。

完整審查範圍、重現及最新驗證見 [規則 11 審查紀錄](docs/algorithm-review-v11.md)。歷史回放文件不覆寫；新報告為 `docs/staging-v11-*`，既有 estimator-only 回放時間線與規則 10 相同。

本輪 JDK 21.0.11 驗證：**262 項 JVM tests、13 項一次性 Android 16 模擬器 instrumentation 通過**；Lint 26 warnings／0 errors；Debug、AndroidTest、R8 unsigned Release APK 建置成功。沒有驗證實機整夜耗電或生理分期準確度，沒有操作實體手機。

### 規則 10 歷史交付（本輪延續保留）

針對 `ca609b3` 的 review 修正已完成；App `versionCode=2`、分期規則 10、正式動作摘要 v7／要求 10 Hz，motion／sleep DB schema 仍為 4／11。這是供新一晚收集資料的工程測試版，不是分期準確度、背景完整性或耗電已獲證實的結論。

- **小缺口不再重複扣分**：v7 已允許單次 gap ≤ 500 ms、每分鐘總缺漏 ≤ 1,000 ms，不再額外套用舊 1 Hz「15 分鐘最多一個缺口分鐘」預算。原錯誤可使 120 個分鐘全部 `canStage=true`、感測覆蓋 99.9333% 卻 Deep=0；固定對照修正後為無缺口／小缺口各 66 分鐘。超過 v7 預算仍阻擋，未把缺口補成安靜。
- **按代表點水位封存分鐘**：raw timestamp 已越過分鐘邊界但正規化 timestamp 尚未越過時，不提早 drain。避免尾端時間另存成同分鐘碎片，或缺口碎片覆蓋必要特徵；即時與整批輸入結果一致。v6 相容路徑及停止時 partial flush 保留。
- **詳情、CSV 與正式整理使用完整觀測窗前文**：只向前讀 60 分鐘不足以重建幾小時前建立、其後由單次動作續期的耦合；現在共用 `reconciliationEvidenceStart`，由包含 session 的窗口起點再向前保留 sparse 前文。
- **診斷不冒稱套用另一條公式**：v7 沒有 P70 相對安靜入口，`relative_quiet_threshold`／`relative_quiet_method` 留空；v6 與更舊相容路徑仍保留實際使用的 P70。原始 nightly P70 統計欄可保留，不能當成生效門檻。
- **詳情可捲動**：長篇阻擋原因／同步錯誤不再把時間軸擠出可讀區域；關閉與修正時間按鈕保留。規則升至 10 會透過既有 dirty/generation 流程重算近期紀錄，不清資料、不改人工起訖、不虛構深眠。

JDK 21.0.11 執行 `testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest assembleRelease --no-configuration-cache`：**243 項 JVM 測試通過**，Lint 26 warnings／0 errors，Debug、AndroidTest 與 unsigned Release APK 建置成功。一次性 `-read-only -no-snapshot` Pixel_10_Pro 模擬器（Android 16）實際執行 **12 項 instrumentation tests 通過**：9 項儲存／升級、2 項觀測窗口、1 項服務／首頁／CSV／詳情整合。服務測試走模擬器 sensor callback、等待分類、要求 10 Hz、低電量 flush、接電恢復與首頁實測率刷新；CSV 測試使用正式按鈕、日期選擇及匯出 callback，僅把文件選擇器目的地替換為測試 cache，確認 header／row 及 capture 欄位。詳情測試確認長文字可捲到完整時間軸且關閉按鈕可見。未操作實體手機，沒有驗證真實 Health Connect 寫入、Google 分類時機、OEM／Doze／FIFO 喚醒或整夜耗電。

新增六項 JVM regression 見 `TenHertzReviewRegressionTest`；原始事件 → v7 → AUTO → 分期的 180 分鐘合成案例有 99.9325% 感測覆蓋、155 個基準分鐘，輸出 86 分鐘 Deep 且每段至少 10 分鐘。舊真實夜間的去識別 **1 Hz** 摘要回放維持 62 分鐘 Deep；60／70 分鐘沒有進入新公式或補滿邏輯，這些數字不是新一晚的預測，也不是生理標籤。`docs/staging-v10-*` 是既有 estimator-only fixture 的新規則報告，歷史 v7／v8／v9 文件與 fixture bytes 不變。

### 今晚與隔日回傳

1. 使用已簽署 Debug APK `app/build/outputs/apk/debug/app-debug.apk`（1.0.1），相同簽章可覆蓋安裝；若簽章不相容，先處理簽章，不要為安裝測試版刪除原有紀錄。unsigned Release APK 不能直接安裝。開啟 App 一次，確認自動記錄未暫停、排程正確，依首頁補齊必要授權。
2. 正常放置手機並記下睡前／起床電量、是否充電、手機位置與大致實際起訖。新 capture 啟動後，匯出卡應顯示目標要求與特徵正規化上限各 10.00 Hz；開始前可能仍顯示舊 capture，不能據此判斷新政策未生效。原始事件實測率可不同於 10 Hz，且不是 CPU 喚醒率。
3. 隔日觀測完成後保留睡眠詳情時間軸截圖，匯出時選**實際資料所在日**。例如 10 月 2 日凌晨至早上要選 `2026-10-02`，不能直接採用預設的昨天。跨午夜且前一日已有採集時，前後兩日 CSV 都匯出；沒有 session 也照常匯出分鐘診斷。摘要僅保留 14 天。
4. 回傳 CSV、詳情截圖及電量／充電情況後，先核對 feature version、target/request/raw event rate、sample count、coverage／gap、耦合與基準，再分析 Deep 進退與 Light 回退原因。不因少於 60 分鐘就補滿，也不因超過 60 分鐘就宣稱準確。

## 自動記錄睡眠

2026-10-01 規則 11 起床／延長觀測（僅適用非全天窗口）：排程結束是重新評估的時間，不是仍在睡眠時的硬性停止點。排程後半段先有至少早 30 分鐘的 confidence ≥ 80 睡眠證據，才評估連續 confidence ≤ 20 的起床回報。低分串起點若在原排程結束前一小時內或延長期間，只需至少 2 筆、跨度至少 10 分鐘；更早則仍需至少 3 筆、跨度至少 20 分鐘。兩條路徑都要求相鄰回報不超過 15 分鐘、最後一筆距現在不超過 10 分鐘。回報缺口及前半夜資料只重設當串證據，不會永久阻擋後續合格的起床串；關閉時間取有效低分串起點。單筆低分、缺資料或完全靜止不能提早關閉；更早時段保留 20 分鐘以降低夜間短醒誤關窗，末段 10 分鐘仍可能受誤分類影響。這是未校準工程門檻，10 分鐘是回報的時間跨度，不是實際醒來後的保證反應時間。

接近排程結束前 20 分鐘起，最新分類仍為 confidence ≥ 80 且距現在不超過 20 分鐘時，實際觀測結束延至該證據之後 30 分鐘；持續新睡眠回報可續延。續延需 now 不大於目前 effectiveEnd，且回報時間仍位於既有觀測範圍；恰在有效結束時可合法續期，超過後即使窗內高分晚到也不能重新延長。沒有近期睡眠支持時在目前有效結束時間完成觀測，不把未知或手機靜止當成睡眠。最多延至下一個排程開始，避免平日／週末窗口重疊；下一窗可接續觀測。門檻未校準，分類延遲／漏失仍可能影響結果。

實際窗口以原排程起訖為鍵、有效結束及 closed 標記保存於已排除備份的 sleeptrace_motion；先同步 commit 成功，再通知工作。重啟及遲到回報不重新打開已完成窗口。所有 schedule 消費端（FGS、分類訂閱、鬧鐘、motion／API 候選、分期、UsageStats snapshot、Health Connect 完成檢查）使用同一有效窗口；設定畫面仍顯示使用者指定的時間。排程變更使用新的原始起訖鍵；新窗口不能套用舊快照。未完成窗口不統計；閉合窗口仍需現有候選證據與至少 30 分鐘有效睡眠，不能僅憑判定起床生成睡眠。暫停、低電量、正式 10 Hz 政策與系統背景限制照常生效。有效窗口標記保留 14 天並在讀取排程時清理。以下歷史驗證中「排程結束後才統計」及當時的 1 Hz 政策都是舊版行為。

1. 首次開啟先設定平日偵測時段；可選擇為週六、週日各使用同一組週末時段。跨午夜時依睡眠窗開始日套用，國定假日不會自動識別。每次開啟 App 都會自動檢查活動辨識、通知、Health Connect 與使用情況存取。可由 App 發起的權限會直接交由系統要求，使用情況存取則開啟 Android 系統設定頁。
2. 平日及可選的週末時段在同一個對話框內以 24 小時制拉選欄位設定開始／結束，會即時標示跨午夜狀態；不使用時鐘式選擇器。
3. 完成設定及必要授權後，App 在偵測時段內先等待 Google Sleep API 的高信心睡眠分類；達到工程門檻後才以 `100,000 µs`（政策目標 10 Hz）要求加速度計。Android／硬體仍可能更快或更慢送達，App 依每筆事件 timestamp 正規化成約 10 個特徵代表點／秒。手機放床上或床邊均由 App 自行評估，不用選擇位置，也不用另外開啟動作偵測。
4. 前景服務只在實際觀測窗內執行；排程時間用於啟動觀測，起床後可提早結算，仍有近期睡眠證據時可超過排程結束繼續觀測。實際觀測窗的邊界由獨立鬧鐘接收器切換，睡眠窗外仍訂閱 Sleep API 區段事件。Android 12 以上允許「鬧鐘與提醒」後，鬧鐘可準時啟動邊界；若略過，App 仍設非精準鬧鐘並嘗試從睡眠分類回呼啟動，但 Android 可能拒絕或延遲背景服務，造成動作資料缺口。首頁可重新開啟特殊存取設定。前景通知只在睡眠窗服務執行時顯示，App 或通知可暫停整體記錄。開機、App 更新或程序回收只會在睡眠窗內嘗試恢復；若 Android 強制停止或 OEM 限制背景啟動，回到 App 後且當下位於睡眠窗才會補啟動。明確暫停後則保留暫停，直到按恢復。
5. 首頁只呈現睡眠紀錄、排程與必要連線狀態，不顯示感測器設定或動作時間軸；並顯示本機已保存的最後一筆 Sleep API 睡眠信心與回報時間，不會為此發起即時查詢，且該分數不是經過校準的準確率。睡眠紀錄的工程分數／理由可在自選詳情查看。
6. 完成 Health Connect 系統授權後，App 自動同步睡眠紀錄。有效 session 對外只顯示 Awake、Light、Deep；證據不足的睡眠片段回退為 Light，但診斷仍保留缺資料／耦合／基準等原因，不能把回退 Light 當成生理淺眠證據。不辨識 REM，也不需要開啟 App 或按確認上傳。可選擇修正時間，儲存後會自動更新。

### 電力與硬體策略

首次完成時段與授權引導後，App 會檢查 Android 電池限制。未排除最佳化時會開啟系統允許背景執行的請求；若已被明確限制，則開啟 App 設定，請在電池選項選擇「不受限制／無限制」。返回 App 後重新讀取實際狀態。取消或未調整仍會繼續記錄，不會下次開啟又自動跳轉，首頁保留「允許整晚背景記錄」入口。

小米／Redmi／POCO 僅在首次流程一次性引導「自啟動／背景自啟動」設定。請允許眠迹，並將 App 電池策略設為「無限制」。部分 MIUI／HyperOS 不支援直達頁面時，App 會退回應用程式／一般設定，可搜尋「自啟動」；首頁不常駐顯示小米自啟動區塊。

以上設定需由使用者在系統介面操作，App 不會自行修改。解除限制不會提高取樣頻率或新增持續喚醒，仍採用下列省電策略；也不保證能避開全部廠商背景限制或強制停止。

| 情況 | 要求取樣頻率 | 要求硬體批次回報 |
| --- | --- | --- |
| 睡眠窗前 15 分鐘 | 不取樣；預熱 classify | 前景服務尚未啟動 |
| 睡眠窗內，Google 尚未判斷入睡 | 不取樣 | classify 訂閱及前景服務已啟動 |
| Google 睡眠信心值 ≥ 80、有 FIFO | 10 Hz（`100,000 µs`） | 使用硬體宣告 FIFO 容量的 80% |
| Google 睡眠信心值 ≥ 80、無 FIFO | 10 Hz（`100,000 µs`） | 不支援批次 |
| 未接電且電量 ≤ 15% | 暫停 | 接電或電量恢復後重新評估 |
| 窗外且距下一睡眠窗超過 15 分鐘 | 不取樣 | 僅訂閱 Sleep API 區段；前景服務停止；有特殊存取時設精準鬧鐘，否則用可能延遲的非精準鬧鐘 |

睡眠分類是 Google Play services 定期提供的推估，不是即時或確定的入睡事件；官方舉例可能約每 10 分鐘回報。睡眠窗前 15 分鐘至窗結束才訂閱分類，其餘時間只訂閱區段事件，以減少白天不需要的分類回呼。預熱 classify 不會啟動 FGS 或加速度計，但其中最近 20 分鐘、信心值至少 80 的分類可在睡眠窗開始時觸發取樣。原始分類、區段、每晚 UsageStats snapshot 與本機睡眠紀錄都在同一個 SQLite 資料庫中以交易保存；原始事件有時間索引與 14 天保留期，retention 最多每日檢查並清理一次。既有 SharedPreferences JSON 首次讀取後會遷移並移除，不再為每筆分類重寫完整 JSON。Sleep API 區段會先裁切到平日／週末對應睡眠窗；只在實際觀測窗完成後查詢並保存該窗的 UsageStats snapshot，資料庫以 `(windowStart, windowEnd)` 複合鍵識別窗口，排程結束時間改變就會建立新快照。仍在觀測的當晚不會凍結半窗資料；已有足夠起床證據關閉的窗口則可在排程結束前產生／同步有效候選。完整 snapshot 由 AutomaticPlacement、SleepAnalyzer 與 Health Connect 上傳共用，權限狀態與理由以每個睡眠窗分別判斷，因此來源／位置判斷、reason 與最後扣除的手機使用時間一致；App 偵測使用情況權限由無到有時會排入整理，以更新先前不可用的 snapshot。沒有使用情況存取權時，其他來源仍可推估，並保留無法排除手機使用的說明。若睡眠窗開始已過 2 小時仍無分類，且螢幕已持續關閉至少 2 小時，會啟動同樣 10 Hz 政策的動作備援；它只避免整夜資料空窗，並不能證明使用者已靜止或入睡。這些門檻都是未校準的工程規則，可能延後啟動或整晚未觸發；缺少的前段動作資料不會補成安靜，最終仍可使用 Sleep API 區段及手機使用紀錄推估。

### 非醫療淺眠／深眠與證據限制

目前分期規則版本為 11、正式 10 Hz 動作摘要 feature 版本為 7；詳細規則、資料相容性及欄位定義見下方「目前分期規則」。只在已成立 session 內分析；有充分、可比較的低活動證據時才推估 DEEP，其餘已接受睡眠輸出 LIGHT。缺資料、無耦合、基準不足或低訊號差異仍會阻擋 Deep，並保留正式 reason codes；回退 Light 只是輸出策略，不是淺眠生理證據。AWAKE 僅由已知清醒／精確手機使用覆蓋，手機靜止與 Google confidence 均不是深眠機率。

新動作資料以事件 timestamp 的固定 100 ms cadence 正規化成約 10 個特徵代表點／秒，再彙總為 v7 分鐘摘要；不因 FIFO 批次一次送達就把事件擠在同一時刻，也不保存原始波形。既有 v6 固定 1 秒摘要及 v5／v4 cadence 都保留明確相容路徑；v1／v2 保留讀取，v3 保留活動衝突。基準不混版本，storage priority 為 v7 > v6 > v5 > v4 > v3 > v2 > v1。v7 每分鐘單次缺口不超過 500 ms、累積缺漏不超過 1,000 ms 時仍可通過品質檢查；這些已接受的小缺口不再重複消耗舊版 15 分鐘窗口的單次缺口額度。超額缺口、手機使用、guard、版本或錄製邊界仍阻擋 Deep；無法細分的已接受睡眠片段回退 Light。耦合使用 SUPPORTED／有限 HELD／INSUFFICIENT，不將安靜直接當床邊，不永久延續已過期的支持；內部 `UNKNOWN` placement 是證據狀態，不是輸出的睡眠階段。

時間軸、統計與 Health Connect 共用 AWAKE／LIGHT／DEEP 三階段的標準化毫秒時間線；詳情不再顯示斜線／未判定時數，legacy `SLEEPING` 亦防禦性正規化為 Light。詳情仍說明「沒有資料」「沒有床面動作支持」「沒有基準」等證據限制，並明示 fallback Light 不代表有淺眠生理證據。首頁仍以睡眠紀錄及記錄狀態為主，只在動作匯出卡附上最近一次「要求頻率／原始事件實測頻率／特徵正規化上限」文字。沒有醫療分期、REM 或 PSG 準確度保證。

資料庫使用 SQLite WAL；歷史 session 的同步狀態、版本與時間修正都以 id 做單筆 upsert，不會因單筆狀態變更清空並重建整張 `sessions` 表。reconcile 的合併／撤銷範圍以最近 48 小時重疊的 session 加上仍為 PENDING／SYNCING／FAILED_RETRYABLE 的舊 session 為主；為避免任意 48 小時截止點截斷同一晚證據，讀取端會向前涵蓋重疊 session 的完整排程窗及額外 60 分鐘 sparse-coupling 前文，但不因此擴大歷史寫回範圍。永久同步失敗不會把掃描範圍拉回數月前。時間修正、手動重試、權限恢復及新事件會更新 work generation／dirty flag，讓 `KEEP` 工作在執行期間發現新變更後重新整理。合併後只在同一個 transaction 內刪除被取代的未同步列、upsert 新增／版本／退休列，未變更歷史不會重寫。`replaceSessions` 僅保留給明確的完整重算／遷移用途。`sessions` 依開始時間與同步狀態建索引，支援 limit／offset 及只讀摘要欄位；首頁只讀最新 5 筆（最近睡眠加最多 4 筆歷史），不解析清醒區間 JSON。歷史紀錄用 RecyclerView 分頁載入，追加頁面只通知插入範圍，選取後才按 ID 讀取該筆詳情與清醒區間。首頁只查詢最新一筆 classification，前景服務只查詢最近 20 分鐘的樣本；`MotionStore` 由 `SleepTraceApplication` 共用，避免服務與背景整理各自持有 SQLite helper。首頁骨架在 Activity 建立時建立一次，資料刷新只更新既有 View 的文字、Badge 與 visibility。

睡眠窗內的背景服務監聽 `ACTION_BATTERY_LOW`／`ACTION_BATTERY_OKAY` 及接／斷電事件；精確電量在配置需要時以一次性的 `ACTION_BATTERY_CHANGED` 快照取得，不因每 1% 電量變化持續喚醒。螢幕 ON／OFF 只更新記憶體中的狀態，不重查排程、SQLite 或電量。未改變的下一個睡眠窗邊界不會重設鬧鐘。背景整理對 Sleep API segment 使用時間範圍查詢；分類與動作的主要範圍為最近 48 小時，但對跨截止點的 session／排程窗會讀取完整跨度及 60 分鐘耦合前文。未完成同步的舊 session 仍會擴大 segment 起點以保留匹配能力。`MotionStore.append` 會在單一交易內先讀出批次涵蓋範圍的既有分鐘，避免逐筆建立 Cursor。立即 reconcile 使用 WorkManager `KEEP` 合併重複觸發；持久 generation 也追蹤 session 編輯／重試及 Health Connect、UsageStats 權限恢復，Worker 若執行期間收到新變更會再整理。每日 24 小時 recovery 有 6 小時 flex 並要求電量非低。首頁只在有待整理／待同步資料時安排立即 reconcile。首頁的 DataStore、Health Connect 權限與 Android 背景狀態讀取也由 `HomeViewModel` 的 I/O 工作收集後一次更新畫面。

Health Connect 待同步 session 會先驗證、保存 `SYNCING` 狀態，再以多筆 `SleepSessionRecord` 批次寫入；每個請求最多 1,000 筆，較大的佇列切成多批。暫態 batch failure 將該批標成 `FAILED_RETRYABLE` 並停止；永久 batch failure 會逐筆 fallback，成功的紀錄照常同步，只有單筆仍被拒絕才標成 `FAILED_PERMANENT`。永久失敗不會被每日 recovery 再次提交；使用者可從詳情手動重試，取得 Health Connect 權限時也會重新排入。舊版 `FAILED` 會遷移為可重試狀態。穩定 client ID／revision 保留供安全重試。分期區間與 session 一起保存；`SLEEPING` enum／舊 JSON 值僅保留讀取相容，標準化後對外與 Health Connect 都輸出 AWAKE／LIGHT／DEEP。Health Connect 階段使用 AndroidX API 常數，不 hardcode 數值。時間軸顏色取自主題 primary、secondary、error 與次要文字色，Android 12 以上可套用系統動態色彩。自動放置與分期門檻尚未跨機型／床墊校準，不能視為精度提升證明。

正式政策以 `100,000 µs` 為 10 Hz 目標，並依感測器最小取樣間隔調整傳給 `registerListener` 的要求值；只有實際註冊週期不慢於 100 ms 才標記為 v7 10 Hz，較慢的感測器會明確回落成 v6 1 Hz 相容特徵或 activity-only，而不是冒充 10 Hz。批次延遲以 `min(要求週期, sensor.maxDelay)` ×（優先使用保留 FIFO 筆數，否則共用上限）× 80% 換算成微秒，不另設 App 時間上限。若換算結果超過 Android API `Int` 可表示範圍，才限制為 `Int.MAX_VALUE`。`samplingPeriodUs` 只是要求提示，Android／硬體可能更快或更慢回報；原始事件實測頻率不等於 CPU 喚醒次數或耗電。優先使用帶 FIFO 的 wake-up accelerometer；非 wake-up 或無 FIFO 的感測器在 CPU 休眠時可能漏資料。接電時也維持同一 10 Hz 目標，不會再提高取樣頻率。

不持有持續 CPU wake lock，不開陀螺儀、麥克風、定位或相機。睡眠窗限定 FGS 不會因缺少特殊存取而退回全天常駐。Android 12+ 允許「鬧鐘與提醒」時使用 exact alarm 喚醒邊界接收器；未允許時使用非精準鬧鐘並盡力啟動，但系統可能拒絕／延遲背景 FGS，進而漏掉動作資料。事件本身仍會檢查平日／週末睡眠窗。批次以 SensorEvent 的單調時鐘時間轉換成資料時間，不使用整批送達時刻。

每分鐘累積約 600 個 100 ms 代表點所形成的三軸變化 RMS、活動持續時間、有效覆蓋時間及樣本數；實際數量受硬體事件率、抖動與缺口影響，不能把樣本數單獨當作完整覆蓋。每約 5 分鐘以 SQLite 交易保存分鐘摘要；節流依 HandlerThread 實際處理的 `elapsedRealtime()` 計算，且包含裝置深度休眠時間，因此硬體 FIFO 一次釋放跨多分鐘的樣本時，不會在同一批事件中連續開啟多次交易。停止、暫停或切換模式時會先要求 flush，最多等待 2 秒，再保存已收到資料。系統直接殺死程序可能遺失最後約 5 分鐘尚未儲存的摘要及未送達批次，這些缺口不補成安靜。保留 14 天以上的動作摘要會在新增資料時至多每日清理一次，不保存原始波形，並排除系統備份。

首頁「動作資料匯出」沿用日期選擇與 Android 文件建立器。CSV 保留舊分鐘欄位，新增版本、時間結構、耦合、多個 reason codes、精確子區間、覆蓋遮罩及採集 metadata；缺資料窗口也會輸出。computed 與 stored 結果分開標示，不查新的 UsageStats，不寫回或觸發同步；欄位語意與閱讀方式見下方 CSV 診斷段落。逾 14 天或尚未落盤的摘要可能缺失，沒有完整原始波形可回算。

### 試驗規則

- 每分鐘有效覆蓋至少 45 秒才判讀；長間隔、倒序、重複或非有限值樣本不增加覆蓋。
- v7 以相鄰 100 ms 差與精確 1 秒 lag 差的較大值作動作能量，v6 則使用相鄰 1 秒差；能量 ≥ 0.15 m/s² 算活動。v7 會合併同一短脈衝在 1 秒 lag 通道造成的回聲事件，避免一個物理動作被計成兩次。活動時間比例 ≥ 5% 或變化 RMS ≥ 0.20 m/s²，標示該分鐘有動作。這些是可調的工程起點，未以 PSG 校準。
- v7 10 Hz、v6 1 Hz、v5 時間結構與 v4 cadence-anchor 相容路徑的 BED／QUIET 可以累積 20 分鐘安靜證據；v3 activity-only 的 BED／ACTIVE 可用來結束區段或降低既有候選的參考分數，v3 QUIET 與 v1／v2 資料不建立候選。完整區段至少 30 分鐘，才成為備援候選。手機使用、缺失／低覆蓋資料、錄製重啟、特徵版本切換或持續活動 5 分鐘會切斷區段；短暫翻動不等於清醒。同一時段的多個合格安靜段會分別保存，避免分段睡眠只留下最長一段。
- 若床上動作有效資料至少 30 分鐘且涵蓋候選一半以上，而其中活動分鐘占比 ≥ 30%，Sleep API 候選分數降低 30 分。重疊來源依調整後的參考分數自動選擇，平手優先 Sleep API；低分不阻擋同步。
- 舊版需要人工處理的紀錄會自動轉入同步佇列。完全沒有候選、有效睡眠不足 30 分鐘或舊紀錄缺少已扣除手機使用的時間明細，App 會自動略過，不要求人工裁決。尚未授予使用情況存取權時，App 使用其餘資料推估並保留限制說明。
- 新候選若跨越多筆破碎歷史紀錄，會保留其中一筆穩定 ID 作為新版；其餘已同步的 ID 先從 Health Connect 移除，成功後才送出新版，避免因保守跳過而長期不更新或留下重複資料。
- 手機使用區段在本機扣除，並以 Health Connect 的 AWAKE 階段寫入；其餘時間由同一標準化時間線寫入 LIGHT／DEEP。缺少 Deep 證據時回退 LIGHT，同時保留證據限制診斷；這個映射不能解讀成生理淺眠。
- 首頁不放感測診斷圖；動作匯出卡只用兩行文字顯示最近一次要求、原始事件實測及特徵正規化上限；上限不是實際接收或有效代表點頻率。單筆睡眠詳情只顯示 Awake／Light／Deep 時間軸，另以文字說明 fallback Light、具體證據限制與非醫療用途，顏色沿用主題及動態色彩。
- 睡眠詳情與時間修正對話框由 `SleepDialogHelper` 集中管理；時間修正同一頁同時選擇入睡／醒來時間並即時計算總時長。歷史卡片超過摘要上限時可用「查看全部紀錄」開啟可滾動清單。
- 保留紀錄 ID 與歷史。睡眠起訖／清醒時間變動時增加 `clientRecordVersion` 並更新同一筆；內容相同不重傳，手動修正不被自動推估覆蓋。同步進行中若資料改版，舊請求不能覆寫新版。
- 同步暫時失敗時由 WorkManager 自動重試，採 10 分鐘起的指數退避；系統可能延後背景執行。永久性錯誤回傳 failure，不再無限喚醒裝置；程序中斷後會以相同 ID／版本恢復。缺少 Health Connect 授權時保留紀錄，授權後或下次 App 開啟、定期工作時自動繼續。Android 的系統授權不能由 App 自行同意。
- 背景整理的寫回範圍預設為最近 48 小時；未完成同步的既有紀錄仍會納入。重疊截止點的 session 會讀完整排程窗與前 60 分鐘耦合證據，再明確把候選限制回整理範圍。多個 Sleep API 區段的手機使用資料會在同一輪合併查詢，避免逐段重複掃描 UsageStats。
- `AutomaticPlacement` 使用只向後看的雙時間尺度耦合證據：fast 路徑維持至少 20 個有效分鐘、30 分鐘內 3 個合理短動作且首末相隔 8 分鐘；若動作較稀疏，sparse 路徑仍要求 3 個短動作，但需 60 分鐘內至少 40 個有效分鐘且首末相隔 30 分鐘。建立後安靜維持 HELD 最多 45 分鐘，只有新觀測到的合格動作刷新期限；過期會丟棄已消耗的長窗口歷史，不用舊證據加一次新動作復活。使用、拿起／姿態突變、超過 2 秒缺口、錄製／版本／睡眠窗邊界切斷。這只是訊號耦合啟發式，不宣稱物理位置辨識。
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

`SleepScheduleTest` 涵蓋平日／週末時段與跨午夜邊界；`SleepUsageSnapshotTest` 檢查手機使用區間、理由文字、每窗權限狀態及窗口結束時間鍵；`AutomaticSyncTest` 覆蓋永久錯誤不會自動重試、批次永久失敗逐筆隔離，以及暫態錯誤保持整批可重試。`MotionEngineTest`／`SleepStageCalibrationTest` 涵蓋正式 10 Hz 要求、FIFO 規劃、10／25／50 Hz callback 的 timestamp 正規化、抖動／批次／缺口、v7 > v6 相容優先序、短脈衝保留，以及 v7 15 分鐘嚴格進入與短於 10 分鐘 Deep 整段回退 Light；舊 1 Hz v6 另走明確相容測試。`MotionRuntimeTest` **僅供可丟棄的模擬器**：會改測試 App 時段、授權並模擬分類與電池狀態，檢查等待分類、觸發取樣、摘要保存、低電量暫停與供電恢復。不要對日常使用的實機執行該測試。

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

reconciliation rule version 目前為 11，沿用 `SleepStageEstimator.ALGORITHM_VERSION` 作為已保存規則版本。`AutomaticWorkSignals` 在保存版本低於目前版本時視為 dirty，讓升級後沿用既有 KEEP 工作重算最近 48 小時的寫回範圍；跨過截止點的既有 session 會讀完整 session／排程窗及額外 60 分鐘耦合前文，避免部分輸入洗掉既有階段。規則遷移時也會重新驗證完整結束睡眠窗內、未手動修正的自動 session。新版不再產生且未同步的列保留在本機並標成 `SKIPPED`；已同步或可能已送出的列保留為 `RETIRED`，由既有 Health Connect `clientRecordId` 刪除流程處理，成功刪除後才改為 `SKIPPED`。手動修正與新版仍產生的候選不撤銷；不會為此直接刪除本機 session。Health Connect 的退休刪除與新增寫入共用程序內互斥，避免 immediate／periodic worker 交錯而讓舊請求在刪除後重新寫入。只有 reconcile 資料交易完成且 generation 仍相同時才記錄新版本。

## 目前分期規則：Light 回退、耦合及可觀測特徵（2026-10-01 更新）

以下是目前實作；前面按日期保存的驗證紀錄描述各次歷史版本，不代表目前規則。

有效 session 使用同一 `sleepParts()` 毫秒時間線輸出三種階段：AWAKE 是已知清醒／實際手機使用；DEEP 需要正向低活動證據；其餘已接受睡眠一律輸出 LIGHT。`SLEEPING` 只保留為舊資料解碼相容值，正規化時防禦性轉成 LIGHT，不再成為 UI、統計或 Health Connect 的第四階段。未成立候選、session 外或排程空白仍不會補成睡眠。無 motion、無基準、無耦合、低訊號差異及 stage 空白／矛盾會阻擋 Deep 並留下正式診斷，但回退 Light 不代表有淺眠生理證據。清醒採裁切後聯集，幾秒使用只扣幾秒，首尾清醒保留；AWAKE 優先。深 + 淺 = 睡眠，睡眠 + 清醒 = session 跨度，全部先計毫秒。

詳情顯示「推估深眠」「推估淺眠」及已知清醒，不再顯示未判定時數、斜線或第四個圖例；legacy `SLEEPING` 也畫成 Light。詳情固定說明證據不足片段採 Light 回退，若有診斷則另列現行特徵有效分鐘、床面動作支持、基準與完整 Deep 判斷窗口，而不只顯示籠統的「感測資料不足」。原因文字是用目前規則與目前排程做的唯讀檢查，不會改寫已保存階段；若使用者後來改過排程，歷史夜晚的即時原因可能與當時條件不同。首頁動作匯出卡顯示最近採集的政策要求、原始事件實測與特徵正規化上限；上限不是實際接收或有效代表點頻率，也不是耗電或 CPU 喚醒量測。參考分數不是準確率，也不同於 Deep 證據覆蓋。成功寫入 Health Connect 不等於其他 App 已顯示。

耦合參數集中在 `CouplingPolicy`，均為未校準工程值。fast 路徑為近期 30 分鐘至少 20 個有效分鐘、3 個分散短動作且首末相隔 8 分鐘；sparse 路徑仍需 3 個動作，但改為 60 分鐘至少 40 個有效分鐘且跨度 30 分鐘。兩者共用局部安靜底噪乘 3、RMS 絕對下限 0.015 m/s²、活動 0.2～12 秒。手機使用前後 2 分鐘、單次大動作／低頻向量變化 >1.5 m/s²、超過 2 秒缺口及錄製片段／版本／睡眠窗邊界使支持失效。建立後 HELD 期限 45 分鐘，容許安靜半小時仍有耦合，但不能用安靜永久刷新整晚可信。歷史放置標記保留讀取相容，非物理位置保證；耦合本身絕不是睡眠證據。

`MotionAccumulator.CURRENT_FEATURE_VERSION = 7`，`SleepStageEstimator.ALGORITHM_VERSION = 10`，兩者分別表示摘要定義與推估／reconciliation 規則。正式採集以 `100,000 µs` 要求 SensorManager，再按每筆事件 timestamp 映射到固定 100 ms slot，每 slot 最多一個代表點，最後只保存分鐘摘要；較快的 22.55 Hz 等 OEM callback 不會直接累加成不同尺度。v7 的動作能量取「相鄰 100 ms 差」與「相隔 1 秒差」的較大值：前者保留瞬間衝擊，後者讓平滑翻身與既有 v6 門檻維持相近尺度。coverage／longest gap 保留真缺口，不插值、不補零、不延伸前值；即時與 FIFO 同一事件集合應得到相同特徵。v6 固定 1 秒摘要及 v5 舊摘要保持可讀且可分期，storage priority 為 v7 > v6 > v5 > v4 > v3 > v2 > v1。非有限／重複／倒序事件不改代表點；拒絕數保存在採集摘要。服務重啟／校時建立不同錄製身份，不跨邊界比較差值。只保存摘要，沒有整晚原始三軸波形。

分鐘新增特徵（舊列為 null；CSV 空白表示沒有測量，與 0 不同）：

| 特徵 | 定義與單位 | 缺漏及邊界 |
| --- | --- | --- |
| covered / missing | 有相鄰事件支持的時間／60000 - covered，ms | 不填補缺漏，首尾部分分鐘保守不細分 |
| longestGapMillis | 相鄰代表點不支持的最長連續事件間隔，ms | 跨分鐘缺口可大於 60000；整晚另累積無資料窗口 |
| RMS / maxDelta | 固定尺度三軸差值時間加權 RMS／最大差值，m/s² | 只計有支持的相鄰點；沒有效差值時 peak 為 null |
| activeMillis / movementEvents | 差值 ≥0.15 m/s² 的時間／由非活動進入活動的分離事件數 | 不是所有未取樣秒內動作的次數；跨分鐘連續活動不重計事件 |
| longestActiveMillis / quietTailMillis | 最長連續活動／分鐘末連續安靜，ms | 延續只跨已觀測有效相鄰點，缺口與重啟歸零；不合格的 v5／v6／v7 分鐘不能維持 Deep；可含前一分鐘連續部分 |
| postureDelta | 每約 10 秒的固定尺度代表點三軸均值，對當分鐘第一組均值的最大變化，m/s² | v7 使用約 100 個 100 ms 代表點、v6 使用 10 個 1 秒代表點；只描述低頻向量變化，不宣稱精確姿態；不足兩組或中途缺口不跨接 |
| recordingId / observedStart / observedEnd | 錄製片段身份及有支持差值的事件範圍 | 重複／重疊新摘要不累加；同分鐘不同片段合併後禁止細分 |

v7、v6、v5、v4 都是可提供 Deep 正向證據的 cadence 路徑，但當晚基準只選至少 10 筆的最高相容版本，不混版本；v1／v2 不提供正向證據，v3 只作活動衝突證據。v5／v6／v7 缺必要時間結構特徵時不可補零取得 Deep 資格。基準排除使用、guard、低覆蓋、無耦合及錄製邊界；全幅變化 ≤max(0.0005 m/s², P50×25%) 視為低差異，不能僅因相對分位數低製造 Deep，該睡眠片段回退 Light。這個品質門檻仍未校準。

先判 `canStage`，再判 `canEnterDeep`／`canMaintainDeep`；`canStage=false` 的意義是不能推 Deep，不是 session 無效。`canStage` 同時要求當分鐘 placement 為 BED 且耦合為 SUPPORTED／HELD；即使舊資料形成矛盾的 `UNKNOWN + HELD`，也以 `PLACEMENT_NOT_BED` 立即退出並回退 Light。正式 v7 10 Hz 證據只能從既有嚴格 15 分鐘連續品質窗口進入 Deep，仍使用同版當晚基準、P50、近期活動、動作事件、guard、耦合與邊界條件；不採用稀疏 P70 相對安靜捷徑，也不把進入前的分鐘回填成 v7 Deep。v7 必須在正式 ENTER 後自然維持；最終 Deep run 若短於 10 分鐘，整段以 `SHORT_DEEP_RUN` 回退 Light。這項後處理只刪除短段，絕不把它補長到 10 分鐘，也不使用整晚 Deep 總量、60／70 分鐘目標或指定資料列。v6 1 Hz 及更舊相容摘要仍保留規則 9 的相對安靜路徑、舊回填與短段確認語意，不能與 v7 基準混用。入睡證據後 20 分鐘及手機使用後 15 分鐘保護阻擋 Deep，本身不代表 Awake。孤立短翻動可維持；持續活動、密集動作或連續高 rolling RMS 退出，耦合／品質失效亦退出 Deep，輸出回到 Light。55% Deep 上限只是安全限制，被裁掉的部分回到 Light；沒有醫療真值、固定睡眠週期或可跨個人泛化的比例保證。

既有 `sleeptrace_motion_2026-10-01.csv` 與去識別回放只含 v5／v6 分鐘摘要，沒有 v7 的 100 ms 代表點或原始事件 timestamp；它們可驗證舊資料相容與「Deep 超過 60 分鐘」這類最後黑箱驗收，但不能驗證 10 Hz 特徵、缺口判定、整夜耗電或準確度。v7 必須另取新的正式 10 Hz 夜間資料，依 feature version、原始事件實測 Hz、feature target Hz、每分鐘 sample count／coverage、gap、分期輸出及電量一起驗證；feature target 只是正規化上限，不是實測有效率。小米健康截圖沒有同晚逐段真值，只作形狀與量級參考，不進入公式。

`motion.db` 非破壞性維持 schema 4；同一組時間結構欄位保存 v7 10 Hz 或 v6 1 Hz 分鐘摘要，v1／v2 原列保留，舊資料沒有量到的新特徵仍為 SQL NULL；`capture_runs` 保存政策 target period、傳給 SensorManager 的 period、sensor min/max delay、FIFO reserved/max。舊 schema 3 的政策 target 可由隔離實驗 trigger 還原為 1 Hz／2 Hz；當時未保存的 sensor min/max delay 與 FIFO reserved 保持 SQL NULL，不把未知冒充量測到的 0。`sleep_events.db` 維持 schema 11 保存每筆 session 的 nullable `stageAlgorithmVersion`、`stageFeatureVersion`；舊結果無來源顯示舊版／來源不明。規則 11 透過全域 dirty/generation 觸發近期重算，不新建平行排程；legacy SLEEPING／空白／矛盾階段在標準時間線正規化成 Light。沒有可相容的現存摘要時仍保留 session 與其時間界線，不能虛構 Deep；規則撤銷亦需窗口仍有 Sleep API 原始輸入。人工起訖不改，重算裁在人工界線內。同輸入／版本輸出確定，只有同步內容真正變更才遞增 revision，保留 clientRecordId；僅版本來源更新不重送。

### CSV 診斷閱讀

沿用首頁日期匯出。沒有 motion 的 session 分析分鐘也列出，量測欄位空白；不發起新的 UsageStats 查詢，也不觸發重算寫回。主時間線分段／本地統計／Health Connect 都來自 `sleepParts`；CSV 的 computed 表示當下離線推算，stored 表示已保存結果，不能混作同一版本結果。

- `reason_codes` 保存本分鐘正式適用決策路徑的多個原因；`primary_reason` 按固定順序取主要原因，缺品質時不讓 onset guard 掩蓋缺資料。`SAFETY_CAP` 與 `SHORT_DEEP_RUN` 後處理優先；成功 ENTER／MAINTAIN 則分別以 `ENTER_DEEP`／`MAINTAIN_DEEP` 為主因。`ALLOWED_MINOR_GAP`、`RELATIVE_QUIET_ENTRY`、`RELATIVE_QUIET_CONFIRMED` 是 non-blocking 資訊，不會覆蓋正式 action；後兩者只描述 v6 1 Hz 舊摘要的相容入口，正式 v7 不採用。舊相對安靜路徑成功時，嚴格 15 分鐘路徑的 hypothetical blocker 只留在專屬 `window_blocking_*` 欄，不污染正式 reason codes。entry branch 不適用時，其 entry／window 診斷仍保留。原因包括 NO_SLEEP_EVIDENCE、PHONE_IN_USE、ONSET_GUARD、MISSING_MOTION、INSUFFICIENT_COVERAGE、COUPLING_INSUFFICIENT／EXPIRED、PLACEMENT_NOT_BED、BASELINE_INSUFFICIENT、LOW_SIGNAL_DIFFERENTIATION、WINDOW_TOO_SHORT、RECENT_WINDOW_INCOMPLETE、MINOR_GAP_BUDGET_EXCEEDED、ACTIVITY_TOO_HIGH、ENTER／MAINTAIN_DEEP、EXIT_SUSTAINED_ACTIVITY／COUPLING_LOST、SAFETY_CAP、SHORT_DEEP_RUN、LEGACY_FEATURE_LIMITATION。
- `can_stage`、`can_enter_deep`、`can_maintain_deep` 與 `prior_deep`、`current_eligibility`、entry／maintenance decision、formal action／stage、回填／safety-cap 標記、window blocker／interval 可追到每個分析窗口。耦合另外輸出 current blocker、last reset reason、FAST／SPARSE basis，以及兩種窗口的有效分鐘數、合格動作數與跨度；初始缺資料不再黏成整晚的目前 blocker。舊 `staging_event` 只補充轉換，不再是唯一原因。
- `phone_use_overlap_ms` 是精確重疊；`exact_computed_parts`／`exact_stored_parts` 以 `起點epoch ms:終點epoch ms:stage` 分號列出該分鐘內子區間。分鐘 computed_stage 不將幾秒使用擴大成整分鐘 Awake。
- `valid_motion_minute_percent` 是睡眠遮罩內符合 cadence／45 秒覆蓋的分鐘比例；`sensor_coverage_percent`／`span_motion_coverage` 是整個分析跨度的感測覆蓋，分母包含已知清醒。`stageable_sleep_coverage` 保留為具備 Deep 判斷能力的證據覆蓋／睡眠時間，不是 UI 是否有階段輸出；fallback Light 不得灌大這個比例。partial minute 與使用相交處的 sensor 覆蓋仍是按時間比例近似，不能還原未保存的逐秒覆蓋分布。
- `first_motion_delay_minutes` 是首筆現存 motion 摘要／事件的延遲；`first_valid_motion_delay_ms` 是第一次完成 15 分鐘連續感測品質窗口的時間，不代表已具備 BED、耦合、基準或 Deep 資格，也與入睡證據無關。無有效窗口輸出空白，不輸出 0。`night_longest_gap_ms` 與原因時長彙總仍描述證據限制，不因畫面回退 Light 而消失。
- `session_span_ms`、`sleep_ms`、`deep_ms`、`light_ms`、`undetermined_ms`、`awake_ms` 是既有 append-only schema 的 computed 毫秒統計；規則 9 新計算的 `undetermined_ms` 為 0，相關舊欄名保留相容，不能解讀為診斷原因已消失。版本分別列出 computed 及 stored 來源；舊摘要來源不足時 stored 的保留結果可與 computed 不同。
- capture 欄位分開保存 policy target period、SensorManager request period、sensor min/max delay、FIFO reserved/max、政策／要求／實測原始事件／分鐘特徵 Hz，以及 capture 累計 raw/rejected events、平均／最大事件間隔。診斷 schema 3 完整保留 v1 的前 111 欄與 schema 2 尾端欄位，再追加 `light_fallback`／原因／彙總，以及 legacy relative-quiet 門檻與方法；`relative_quiet_threshold` 是 v6 相容路徑的 P70，P50 仍由既有 `nightly_p50` 欄提供，方法為 `NIGHTLY_P70_PROVISIONAL_CONFIRMED`，正式 v7 嚴格入口不讀這組欄位。品質不足、safety cap 與 `SHORT_DEEP_RUN` 撤回都必須標為 fallback Light。`diagnostic_schema_version=3` 保留在既有 schema 2 位置。註冊時及 5 分鐘摘要批次／結束 flush 保存，不做逐 callback 資料庫寫入；capture 累計值會在多個分鐘列重複，不能跨列相加。數值固定小數點，字串有 CSV escaping；未評估／舊資料未保存的欄位留空，不以 0 冒充，不輸出帳號／App 使用清單或原始波形。

### 隔離採集實驗

Release 永遠忽略實驗設定；Debug 預設 OFF，沒有一般使用者必選 UI。僅 Debug manifest 包含 `CaptureExperimentReceiver`，有效值 OFF、EARLY_1HZ、EARLY_2HZ。OFF 現在代表正式 10 Hz 與正常啟動政策；另兩種模式是提早啟動的 legacy 低頻比較，不是 Release 政策。用受控目標裝置執行：

```powershell
# A：正式 10 Hz／正常 Sleep API 觸發政策
adb.exe -s <序號> shell am broadcast -n com.rsps1008.sleeptrace/.motion.CaptureExperimentReceiver --es mode OFF
# B：只在睡眠窗內較早採集，legacy 1 Hz／v6 分鐘特徵
adb.exe -s <序號> shell am broadcast -n com.rsps1008.sleeptrace/.motion.CaptureExperimentReceiver --es mode EARLY_1HZ
# C：必要時才比較；只在睡眠窗內較早採集，要求 2 Hz、仍正規化為 legacy 1 Hz／v6 分鐘特徵
adb.exe -s <序號> shell am broadcast -n com.rsps1008.sleeptrace/.motion.CaptureExperimentReceiver --es mode EARLY_2HZ
```

命令只改 Debug 實驗政策，不自行啟動服務、不繞過暫停／低電量／權限／排程；既有服務重估，下一次合法啟動才採用。切回 OFF 會重新評估正式觸發並恢復 v7 10 Hz。B／C 同時改變啟動時機與低頻採集政策，不能單獨拿來證明 10 Hz 的準確度或耗電差異；C 的 2 Hz 原始要求仍轉成 1 秒尺度 v6 摘要。接電不自動開實驗，無全天 FGS、持續 wake lock、新感測器或每秒輪詢。提前採集不代表提前入睡。

在受控實機分夜比較 A／B，必要才 C；同手機各多晚記錄採集延遲、實測 raw event Hz、每分鐘 sample count／coverage、有效與可分期覆蓋、缺口、主要阻擋原因、起訖電量和背景限制。A 與 B 不只差頻率也差啟動時機，結果必須按 capture trigger／feature version 分開解讀。未知的平板使用若在所有手機輸入上與睡眠相同，App 無法辨別；只保留可看見的手機使用及 API 起點保護，不能保證排除不可觀測情境。

規則 9（ca609b3）的歷史驗證：以 JDK 21.0.11 強制重跑完整 `testDebugUnitTest`，共 237 項 JVM 測試通過、0 failure／error。`lintDebug` 成功，保留 26 條既有 Warning、0 Error；Debug、AndroidTest 與 unsigned Release APK 全部建置成功，`git diff --check` 無錯誤。另以 UTC 單獨重跑去識別真實夜間回放，仍自然得到 Deep 62 分鐘且 `SLEEPING=0`；此數值只是最後黑箱驗收，不是 10 Hz 規則輸入。Android instrumentation 僅完成編譯，未在可丟棄模擬器或實機執行；也未驗證首頁持續前景更新、SQLite v3→v4 裝置升級、Health Connect 實際寫入／刪除、正式 10 Hz FIFO 喚醒、整夜耗電或 PSG／穿戴真值，因此以上只證明程式、固定特徵測試與舊摘要回放行為，不證明睡眠分期準確度。

### 歷史 V5／V6 驗證快照（已由規則 9 驗證取代）

2026-09-30 從 HEAD `0ee7451859569f62d805e81dd580f5b439a41260` 乾淨工作區整合。Java 21 執行：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease --no-configuration-cache
```

167 個 JVM 測試、0 failure／error；lint 25 warnings、0 errors；Debug／AndroidTest／unsigned Release APK 全部建置成功。沒有刪除重要既有測試；缺資料 LIGHT、跨缺口 entry、UNKNOWN 無記憶 grace 與平坦雜訊 Deep 的舊預期改為 v5 未判定／連續品質語意，另保留正向 Deep、翻身維持、活動退出、穩定 ID、時間守恆、版本邊界及 FIFO／取樣率 regression。新增 formal decision／maintenance 一致性、合法／超預算缺口、回填／safety-cap provenance 與 fixture bytes hash regression；SQLite instrumentation 案例已編譯但未執行。

只找到既有合成 `real_night_style.csv`，沒有真實整晚 raw CSV；沒有虛構實機那一晚結果。凍結 HEAD 的 algorithm 4 對照 algorithm 5（報告 `docs/staging-v5-replay.txt`）：329 分鐘合成跨度，Deep 132→104、Light 197→42、未判定 0→183、Awake 0→0；切換 8→11、<5 分鐘睡眠片段 1→1。有效分鐘覆蓋兩版均 89.6657%；新版跨度感測覆蓋 89.7568%，可細分睡眠覆蓋 44.3769%；未判定主要原因為缺 motion 33 分鐘、覆蓋不足 1 分鐘、耦合不足 149 分鐘。這是工程回歸，不是真實生理準確度、深眠比例優化或與原生／醫療演算法等價的證明。

目前只連接 Mi Note 10 與 Pixel 實體裝置，依測試的可丟棄模擬器限制未安裝、未跑會改資料／權限的 instrumentation。UI 實機目視、SQLite 升級裝置執行、Health Connect 真實寫入／刪除、正式 10 Hz 採集、Google 回報延遲、OEM／Doze／FIFO 完整性、PSG／穿戴對照與整夜耗電均尚未驗證。歷史 1 Hz 摘要不能重建未觀測的秒內動作或 v7 100 ms 特徵；所有耦合／分期門檻仍未以 PSG 標籤校準。Health Connect 成功亦不代表其他 App 已顯示。
# V7 分期證據修正（2026-09-30）

V7 修正 V6 首次建立誤把「當前分鐘必須有新動作」當成額外條件的缺陷：完整歷史已滿足 20 個連續摘要、三個分散短動作與八分鐘跨度時，即使確認分鐘安靜也建立支持；有效期限仍從最後一個合格動作計算。已建立且未失效時一個新動作可續期；過期、handling、使用、錄製／版本／睡眠窗邊界後都必須重新滿足完整歷史。

分期仍只在已接受的睡眠 session 內進行。這段記錄規則 7 當時的缺口語意：缺口例外只可用於完整 60 秒 v5 桶，累積未覆蓋時間以 `60,000 - coveredMillis` 判定，單一 2 秒最長缺口不能掩蓋同分鐘四次漏樣的 8 秒總缺漏；部分桶、遺失資訊或 `covered=60,000` 卻宣稱有缺口的矛盾摘要都走嚴格路徑。當時缺口／無耦合等分鐘保留 `SLEEPING`；現行規則 9 已明確改為 Light 回退，但同樣不得進 Deep，正式 blocker 仍保留。

回放說明見 [`docs/staging-v7-replay.txt`](docs/staging-v7-replay.txt)；其矩陣與差異 CSV 由同一 JVM 回放物件生成，檢查命令不覆寫已提交文件。原 CSV 是 estimator-only，沒有跑 AUTO，且合成回放不是醫療或 PSG 驗證。演算法版本為 7，感測 featureVersion 維持 5。

### V7 診斷一致性修正

本輪只修正診斷與正式狀態機的共用判定，不調整正式分期門檻，也不升版。`SleepStageEstimator.evaluateFormalDecision()` 是每分鐘唯一正式決策入口；狀態更新與 `MinuteDiagnostic` 共用 `priorState`、`currentEligibility`、`entryDecision`、`maintenanceDecision`、`action`、`transitionReason` 及高活動窗口計數。正式維持會使用與退出相同的密集事件、3/5 active、耦合失效及連續 rolling motion 遲滯；孤立活動仍可維持，低訊號差異、版本／錄製邊界與硬中斷不能報告可維持。

短缺口的 `RECENT_WINDOW_INCOMPLETE`、`WINDOW_CONTAINS_GAP`、`MINOR_GAP_BUDGET_EXCEEDED` 與阻擋區間由同一 entry decision 生成。`window_blocking_intervals` 使用 `start..(end-1)` 的 inclusive `LongRange` 表示半開 `[start,end)` 分鐘；合法缺口離開最近五分鐘後只列 `ALLOWED_MINOR_GAP`，不再列為阻擋。`formal_stage`、`was_backfilled`、`safety_cap_adjusted`、`final_stage` 區分當時決策與後續回填／安全上限，不用最終 stage 反推當時維持資格。

本輪另外補齊三項診斷一致性：`prior_deep=false` 時只有 entry decision 的 `applicable=true`，`prior_deep=true` 時只有 maintenance decision 的 `applicable=true`；另一分支的 `allowed` 與 reasons 仍保留作診斷參考，但不能被讀成該分鐘的正式動作。`reason_codes`／`primary_reason` 只合併正式適用分支：maintenance 時不納入 hypothetical entry 的 window blockers 或 non-blocking reasons，但仍在各自 CSV 欄位完整輸出；`SAFETY_CAP` 優先，其次成功 ENTER／MAINTAIN 固定標示 `ENTER_DEEP`／`MAINTAIN_DEEP`，所以合法短缺口的 `ALLOWED_MINOR_GAP` 不會蓋過正式動作。active 3-in-5 的 Deep 回寫只標記實際被改動的分鐘，使用獨立的 `retroactively_adjusted`、`retroactive_adjustment_reason=SUSTAINED_ACTIVITY_REWRITE` 與確認分鐘 `retroactive_adjustment_source_ms`；正式退出分鐘仍只保留自己的 `Action.EXIT`。`was_backfilled`、retroactive rewrite 與 `safety_cap_adjusted` 是互不覆蓋的 provenance 通道，但現行後處理先改寫非 Deep 分鐘、safety cap 只處理剩餘 Deep 分鐘，因此 activity rewrite 與 safety cap 不會在同一分鐘由正式 pipeline 同時產生；不以 `.copy()` 製造該狀態測試。Hard break 的 `transition_reason` 現在只從完整 `maintenanceDecision.reasons` 按固定優先序派生，包含 phone、onset、recording、feature、coupling、coverage 與 sleep-evidence 類原因；不再有第二套 `hardBreakTransition()` 推論。`current_eligibility_reasons` 只表示使當前分鐘不能基本分期的 blocker，ONSET guard 只放在 entry decision，若正式維護路徑遇到 guard 則也放在 maintenance decision。

診斷 CSV 的 header 與 row 共用 `DIAGNOSTIC_CSV_HEADER`／`diagnosticCsvRow()` schema；空字串、`false` 與 `0` 保持不同語意，row 欄位數不符會直接失敗。這些變更只補 provenance／診斷來源，`ALGORITHM_VERSION=7`、`MotionAccumulator.CURRENT_FEATURE_VERSION=5` 與 `real_night_style.csv` 的 estimator-only stage intervals、durations、transition matrix 均保持不變。

本輪驗證：`testDebugUnitTest` 175 項通過（0 failure／error）、`lintDebug` 成功且無 Error、`assembleDebug` 與 `assembleDebugAndroidTest` 成功；`StagingReplayTest` check-only 通過且未改寫 replay docs。沒有可丟棄模擬器／測試裝置可安全執行 instrumentation，因此未執行裝置測試、真實 Health Connect 寫入、實機整夜感測或耗電驗證。

回放 fixture 的 SHA-256 由本次讀取的原始 classpath bytes 計算，再以同一份 bytes 用 UTF-8 解析；沒有手動 hash 常數。`StagingReplayTest` 預設只檢查 `build/reports` 與已提交 `docs/`，不覆寫文件；只有明確設定 `SLEEPTRACE_UPDATE_REPLAY_DOCS=true` 才更新。修正前失敗案例與來源驗證說明見 [`docs/staging-v7-diagnostic-baseline.md`](docs/staging-v7-diagnostic-baseline.md) 與 [`docs/staging-v7-changes.md`](docs/staging-v7-changes.md)。


### 2026-09-30 起床與延長觀測驗證

使用本機 JDK 21.0.10 執行 `testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest assembleRelease --no-configuration-cache`：184 個 JVM 測試通過（含 9 個新增觀測規則測試），lint 25 warnings／0 errors，Debug、AndroidTest 與 unsigned Release APK 建置成功。`.gitattributes` 固定合成回放 fixture 及 v7 報告的 LF 換行，保留 HEAD 原始位元組，解決 Windows autocrlf 導致的雜湊／文字比對失敗；未覆寫回放預期或更改分期演算法。未執行 instrumentation、實機整夜觀測／耗電、Google 真實分類回報及 Health Connect 真實寫入。

### 2026-10-01 observation code review 修正

Review 基準及本次起始 HEAD 為 `ad7209d11db1210918e0eefb4e86bb0744223d7e`，main 分支原先乾淨。三項修正不變更 confidence／freshness／起床持續時間、1 Hz／FIFO、耦合／分期門檻、依賴或 DB schema。

- 全天判斷使用窗口開始日的原始起訖設定及週末繼承，與 DST 的 23／25 小時實際跨度無關。全天不套 observation end，也不做起床 early-close／延長；保留 nominal 及下一窗開始的截斷語意。已完成 nominal 窗口仍由既有 completedWindows、UsageStats 與 analyzer 流程分析。requiresWindowBoundary 僅由原始平日／週末設定判斷，不由 observation map 非空判斷；label 仍顯示原設定。
- 讀取時排除全天 override；只清理開始日適用全天且精確匹配目前 nominal key 的舊 observation。其他一般排程有效紀錄與無法確認的歷史 key 保留（原有 14 天 observation retention 仍生效）。不清除 session、UsageStats snapshot、Health Connect 或其他 SharedPreferences 欄位。
- 一般窗口在既有起床判斷之後、extension 之前檢查 `now > effectiveEnd`，關閉於原有效結束，不接受 timestamp 仍在窗內的遲到高分續期；previous=null 亦適用。`now == effectiveEnd` 的合法睡眠續期保留，closed 立即返回原值，nextStart 上限不變。
- `SleepPreferences.schedule()` 共同入口內部 withContext(IO)；阻塞 SP／SQLite／commit 及 observation 副作用離開 Main。internal 的 Android adapter 只從此入口呼叫，純 repository 供固定 clock／zone／儲存注入測試。同一把程序內 lock 涵蓋讀取、評估、commit、通知，取得鎖後才讀 clock；不能以舊評估覆寫新 closed。成功 commit → dirty → 非同步服務 refresh／reconcile；不在鎖內等待服務回呼。保存失敗明確拋錯且不通知。SP commit 失敗可能已更新記憶體，因此只回復本次受影響 observation keys，避免下一次讀取誤認已持久化；回復失敗也明確拋錯。沒有用 apply 取代 commit，沒有吞取消例外。
- receiver 經 `processSleepClassifications` 保持 appendSamples → 正式 preferences effective schedule → 控制端更新；測試驗證遲到事件先入庫後仍保存 closure，FGS／分類／鬧鐘／UsageStats／候選使用相同有效範圍。MotionService 仍使用原 flush／停止流程，暫停、低電量與活動辨識授權限制未更動。

新增 JVM repository／preferences 測試使用固定日期、UTC／America/New_York、磁碟儲存重建實例、受控 Main 執行緒與明確並行 barrier，無 Thread.sleep。另測實際 SP adapter 的 memory-before-disk 失敗／回復行為。Android integration tests 使用真實 Main Looper／SP／SQLite，資料庫與偏好名稱隔離，且僅允許可丟棄 emulator；沒有啟動服務、要求權限或寫 Health Connect。

修正前新增 4 個重現測試，13 個 policy tests 中 4 項失敗：全天污染造成午後 windowAt=null、505／500／515 遲到續延至 530、無 previous 的 480／475／485 復活、end+1ms 續期。修正後相關 36 個 JVM tests 通過。Main-safety 的舊路徑依來源確認，新增測試在正式 schedule() 強制執行查詢／保存並確認離开受控 Main；沒有宣稱已在裝置執行修正前／後 StrictMode 驗證。

本次實際完整命令（JDK `C:\Program Files\Java\jdk-21.0.10`，SDK 依 local.properties）：

```powershell
.\gradlew.bat testDebugUnitTest --tests '*SleepObservation*Test' --tests '*ObservationPreferencesPersistenceTest' --tests '*SleepScheduleTest' --no-configuration-cache
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest assembleRelease --no-configuration-cache
git diff --check
```

結果：相關 36 項、完整 205 項 JVM tests 均通過，0 failure／error；lint 26 warnings／0 errors（新增 UseKtx 建議在同步回復 SP 的 editor；保留直接 commit 以檢查保存結果）。Debug、AndroidTest、unsigned Release APK 建置成功；git diff --check 通過。205 項含原有 184 項及新增 21 項 JVM regression。新增 2 項 Android integration tests 已編譯，沒有執行 instrumentation。`adb devices -l` 只有清單標題、沒有裝置，因此無可丟棄 emulator；沒有安裝 APK、沒有操作實體手機／授權／Health Connect。未驗證 Android 程序真正終止重啟、實機整夜觀測／耗電、Google 真實 callback 延遲與 Health Connect 寫入。staging fixture／replay 文件、取樣／FIFO、耦合／分期、schema／依賴皆無變更，沒有設定 replay 更新環境變數。合成／JVM 回歸不是生理準確度或實機整夜驗證。

## 2026-10-01 真實夜間稀疏耦合回放與規則 8

`sleeptrace_motion_2026-10-01.csv` 證實該晚不是整夜無資料：session 感測覆蓋 94.326170%、346 個分鐘通過現行特徵基本檢查，但舊 30 分鐘窗口任一時刻最多只有 2 個合格短動作，無法達到 3 個，因此 BED／baseline／stageable coverage 全為 0。另有 02:21 這類 58,993 ms 覆蓋、60 個特徵樣本、1,007 ms gap 的分鐘；它仍不能直接分期，但不能與整分鐘無資料混為同一診斷。基準為空時把現行 feature 與 null 比較而誤加 `LEGACY_FEATURE_LIMITATION` 的問題也已修正。

固定回放使用去識別化的 [`sparse_coupling_night_redacted.csv`](app/src/test/resources/staging/sparse_coupling_night_redacted.csv)：只保留相對 minute index 及 11 個必要分鐘摘要欄位，不含日期、session ID、帳號、App 使用清單或原始三軸波形；LF bytes SHA-256 為 `BC447C6582F3EEE269A2F3C24CF6BB8609B1AD9F7D899E83DDE78F955AC98373`。回放固定走 `AUTO → AutomaticPlacement.resolve → SleepStageEstimator.analyze`，確認六個候選分鐘、30 分鐘窗口最多兩個、原覆蓋率、短缺口分鐘及時間守恆。規則 8 的 sparse 路徑在第三個分散動作時才因果建立，不回填此前時間；本 fixture 產生 92 個 BED 分鐘、76 個 baseline 樣本及大於 0 的可分期覆蓋。測試刻意不要求固定 Deep／Light 分鐘或比例，因為這份資料沒有 PSG／穿戴真值。

規則 8 的 estimator-only 合成回放另存於 [`docs/staging-v8-replay.txt`](docs/staging-v8-replay.txt) 與同名矩陣／差異 CSV；V7 文件維持凍結的 algorithm 7 provenance，不以新版標籤覆寫歷史基準。

該次 capture 的當時政策要求為 1 Hz，但累計 490,746 個原始事件、平均間隔 44.352778 ms，約 22.55 Hz；分鐘特徵仍正規化為約 1 Hz。當時版本選擇保留 1 Hz 並補存 `sensor.maxDelay`／minDelay、FIFO reserved/max 與要求／實測頻率；本次正式政策才升為 10 Hz v7。舊 CSV 沒有逐事件 timestamp 或 100 ms 特徵，不能重建或驗證 v7。FIFO latency 仍按可能的較快硬體事件週期規劃，但不能從 CSV 推算 CPU 每秒喚醒次數或耗電；該感測器為非 wake-up，休眠完整性仍需新一晚實機驗證。

新 v6 resampler 只把相鄰代表點落在 900～1,100 ms 的有效 interval 從 cadence-anchor 誤判中救回；2 秒等真正缺口仍不補成安靜。在規則 8 回放中，舊 v5 CSV 的短缺口分鐘仍為 `SLEEPING`；現行規則 9 則回退 Light，但仍維持 `canStage=false` 且不能進 Deep。這次修改降低可證明的演算法誤判，不代表已證實附件每個 gap 的原始成因。

## 2026-10-01 規則 9：Light 回退與真實夜間回歸

規則 9 明確取代上方規則 8 的對外階段語意：有效 session 只輸出 AWAKE／LIGHT／DEEP。缺資料、低覆蓋、無耦合、基準不足、低訊號差異、活動退出、safety cap，以及舊資料的空白／矛盾／`SLEEPING` 片段都阻擋 Deep 並回退為 Light；正式 reason codes、`canStage` 與窗口 blocker 仍保留，不能因畫面不再顯示未判定，就把 fallback Light 說成有淺眠證據。`UNKNOWN` placement 仍可存在於內部耦合診斷，與使用者看到的階段無關。

去識別真實夜間 fixture 的規則 9 regression 固定要求完整 `AUTO → placement → staging` 管線輸出 0 毫秒 `SLEEPING`，並把「Deep 超過 60 分鐘」只當最後黑箱驗收，不是公式輸入、配額或補滿規則。收緊回填後，本輪回放自然得到 Deep 62 分鐘、Light 302 分 24.032 秒、Awake 1 分 35.968 秒；76 個基準分鐘的 P70 為 0.008399 m/s²。Deep 顯示為 16 段，其中 6 段是一分鐘、具有另一個獨立低 RMS 觀測的確認，但被 Light 或不能分期的缺資料分鐘切開；程式只保留實際合格的低 RMS 佐證點，沒有把兩點之間較高 RMS 的安靜分鐘、缺口合併或補成 Deep。使用者另提供的小米健康歷史畫面只作多段 Deep 形狀與約略量級參考，並非同晚標籤，也不進入公式。這只是該 fixture 的可重現工程輸出；附件沒有 PSG／穿戴標籤，不能據此宣稱該晚真有相同長度的生理深眠，也不能外推為其他手機、床墊或使用者的準確率。
