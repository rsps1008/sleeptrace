# V6 分期證據與回放診斷

V6 修正兩個可重現的程式邏輯問題，而非以降低「未判定」為目標。

- `AutomaticPlacement` 將首次建立與有效期限內續期分開。首次建立仍需 30 分鐘歷史、三個分散短動作與至少八分鐘跨度；已建立且未失效時，一個新的合格動作可以更新最後正向證據。安靜、正常覆蓋與時間經過永遠不會續期。過期、手機使用、handling、嚴重缺資料或 recording/version/window 邊界後，必須重新完成首次建立。
- 邊界先清除舊 history；同一分鐘若同時 handling、手機使用或缺資料，仍會被診斷並排除，不能進入新 history。
- 新 collector 的 featureVersion 5 可辨識單一、不超過 2,000 ms 的短缺口。缺口分鐘仍是 `SLEEPING`；只有同一 recording/version、窗口最多一個短缺口、其餘至少十四個完整合格分鐘且最後五分鐘全完整時，後續完整分鐘才可重新進入 Deep。手機使用、handling、長缺口、邊界或耦合失效均為硬中斷。featureVersion 4 或缺少缺口位置資訊的歷史摘要不適用例外。
- `SleepStageEstimator` 現用同一判斷輸出 current eligibility、window blocking reasons/intervals、baseline reasons 與 transition reason。`canMaintainDeep` 是目前證據條件，而不是以最終 stage 倒推。未有耦合、已過期、資料缺失、基準不足或必要特徵不存在時仍保留 `SLEEPING`，不改名成 `LIGHT`。

演算法版本由 5 升為 6；感測 featureVersion 不變，仍為 5。沒有新增資料庫欄位、感測器、權限、wake lock、輪詢或全天前景服務。既有 session ID、revision 判斷與 Health Connect `SLEEPING` 映射未改。

## 回放範圍

`StagingReplayTest` 是 estimator-only：原 CSV 的 placement、coverage、RMS 與 featureVersion=4 保持原樣，不會呼叫 AUTO。AUTO 端到端行為以 `AutomaticPlacementTest` 的合成分鐘證據回歸；合成結果只驗證程式規則，不代表真實睡眠或醫療準確度。V6 尚需真實整晚 v5 摘要及可丟棄模擬器/實機驗證，尤其是 FIFO、OEM 背景限制與 Health Connect 寫入。
