package com.example.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.PropertyName
import com.google.firebase.firestore.ServerTimestamp

@IgnoreExtraProperties
data class TestSession(
  @DocumentId val id: String = "",
  val userId: String = "",
  val title: String = "",
  val metroLine: String = "",
  val testCategory: String = "",
  val foreignLangCode: String = "en-US",
  val foreignLangLabel: String = "English (英文)",
  val utteranceCount: Long = 0L,
  val minutesSummary: String = "",
  val minutesKeyPoints: List<String> = emptyList(),
  val minutesActionItems: List<String> = emptyList(),
  val minutesTestVerdict: String = "進行中 (In Progress)",
  @ServerTimestamp val createdAt: Timestamp? = null,
  @ServerTimestamp val updatedAt: Timestamp? = null,
)

@IgnoreExtraProperties
data class Utterance(
  @DocumentId val id: String = "",
  val userId: String = "",
  val sessionId: String = "",
  val speakerRole: String = "FOREIGN_ENGINEER",
  val speakerLabel: String = "",
  val sourceLang: String = "en-US",
  val targetLang: String = "zh-TW",
  val originalText: String = "",
  val translatedText: String = "",
  val technicalTerms: List<String> = emptyList(),
  @get:PropertyName("isPinnedToMinutes")
  @set:PropertyName("isPinnedToMinutes")
  var isPinnedToMinutes: Boolean = false,
  @ServerTimestamp val createdAt: Timestamp? = null,
  @ServerTimestamp val updatedAt: Timestamp? = null,
)

data class TranslationResult(
  val translatedText: String,
  val technicalTerms: List<String>,
)

data class GeneratedMinutesResult(
  val summary: String,
  val keyPoints: List<String>,
  val actionItems: List<String>,
  val testVerdict: String,
)

data class ForeignLanguageOption(
  val code: String,
  val label: String,
  val shortTag: String,
  val vendorContext: String,
  val sampleForeignPhrases: List<String>,
  val sampleLocalPhrases: List<String>,
)

val SUPPORTED_FOREIGN_LANGUAGES = listOf(
  ForeignLanguageOption(
    code = "en-US",
    label = "English (英文)",
    shortTag = "EN",
    vendorContext = "國際號誌與系統整合技師 (CBTC / ATC / PSD)",
    sampleForeignPhrases = listOf(
      "Please check the CBTC beacon handover latency when approaching Platform 2 at 45 km/h.",
      "The Platform Screen Door interlock relay took 1.8 seconds to release after ATO stopping accuracy check.",
      "Let's run a traction power emergency brake cut-out test on Track Section B4.",
      "We observed a minor packet loss on the wayside-to-train radio link near the tunnel curve."
    ),
    sampleLocalPhrases = listOf(
      "我們在第二月台進站測試時，ATO 自動停車精準度誤差只有正負 8 公分，符合規範。",
      "請幫我們確認車載控制器 VOBC 的韌體版本是否已經更新為最新測試版。",
      "剛剛月台門與車門連動的開啟延遲有點偏高，我們需要再跑一次緊急手動解鎖測試。",
      "請把這段無線電訊號切換的測試數據列入今天的待追蹤改善事項。"
    )
  ),
  ForeignLanguageOption(
    code = "ja-JP",
    label = "日本語 (日文)",
    shortTag = "JA",
    vendorContext = "日系車輛與轉向架／煞車系統技師 (Rolling Stock / Bogie)",
    sampleForeignPhrases = listOf(
      "台車の空気ばね圧力と回生ブレーキの切り替えタイミングを確認してください。",
      "ホームドアとの連動試験で、扉閉鎖検知信号に0.5秒の遅延が見られました。",
      "非常ブレーキ作動時の減速度は規定値内に収まっています。"
    ),
    sampleLocalPhrases = listOf(
      "請確認轉向架空氣彈簧在過彎路段的壓力感測數據是否正常。",
      "我們準備進行再生煞車與氣軔切換的動態負載測試，請日方技師就位。",
      "第三節車廂的車門防夾感測器靈敏度需要再微調。"
    )
  ),
  ForeignLanguageOption(
    code = "de-DE",
    label = "Deutsch (德文)",
    shortTag = "DE",
    vendorContext = "歐系號誌與軌道轉轍器技師 (Interlocking / Point Machine)",
    sampleForeignPhrases = listOf(
      "Bitte prüfen Sie die Weichenüberwachung und die Stellzeit am Gleiswechsel Nord.",
      "Das Zugsicherungssystem meldet eine stabile Verbindung zum Streckenrechner.",
      "Wir müssen die Notbremsüberbrückung im Tunnelabschnitt noch einmal testen."
    ),
    sampleLocalPhrases = listOf(
      "北側橫渡線的轉轍器扳動時間測試結果為 3.2 秒，鎖定訊號正常。",
      "請協助確認聯鎖系統與行控中心 ATS 的狀態回報是否同步。",
      "我們再進行一次隧道區間的緊急煞車旁路測試。"
    )
  ),
  ForeignLanguageOption(
    code = "fr-FR",
    label = "Français (法文)",
    shortTag = "FR",
    vendorContext = "法系全自動無人駕駛與機電技師 (VAL / Urbalis CBTC)",
    sampleForeignPhrases = listOf(
      "Vérifions la synchronisation entre le pilote automatique et les portes palières.",
      "La redondance du réseau radio sol-train est validée sur toute la zone de dépôt."
    ),
    sampleLocalPhrases = listOf(
      "全自動無人駕駛模式下的月台門對位精準度已經通過初驗。",
      "機廠測試軌的車地無線網路雙迴路備援測試結果正常。"
    )
  ),
  ForeignLanguageOption(
    code = "ko-KR",
    label = "한국어 (韓文)",
    shortTag = "KO",
    vendorContext = "韓系電聯車與車載通訊系統技師 (EMU / TCMS)",
    sampleForeignPhrases = listOf(
      "열차 종합 제어 장치(TCMS) 화면에서 추진 제어 인버터 상태를 확인해 주세요.",
      "객실 방송 및 비상 인터폰 연동 시험을 시작하겠습니다."
    ),
    sampleLocalPhrases = listOf(
      "請在列車監控系統 TCMS 畫面上確認推進變流器的溫度與電流狀態。",
      "現在開始進行車廂緊急對講機與駕駛室連線測試。"
    )
  )
)

val METRO_LINES = listOf(
  "板南線 (BL)",
  "環狀線 (Y)",
  "淡水信義線 (R)",
  "松山新店線 (G)",
  "中和新蘆線 (O)",
  "文湖線 (BR)",
  "萬大樹林線 (LG)",
  "機廠測試軌 (Depot)"
)

val TEST_CATEGORIES = listOf(
  "CBTC 號誌與聯鎖測試",
  "電聯車動態與煞車測試",
  "月台門 (PSD) 同步連動",
  "TCMS 車載監控與通訊",
  "供電系統與第三軌負載",
  "安全驗收與緊急應變 (SAT)"
)

val TEST_VERDICTS = listOf(
  "進行中 (In Progress)",
  "通過 (PASS)",
  "有條件通過 (CONDITIONAL)",
  "待複測 (PENDING RE-TEST)",
  "異常需追蹤 (ISSUE OPEN)"
)
