package com.example.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.example.data.model.TestSession
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ListeningChannel {
  NONE,
  FOREIGN_TO_LOCAL,
  LOCAL_TO_FOREIGN,
}

/**
 * Immutable snapshot of the active session and direction captured at the moment
 * speech recording starts. Guarantees that switching sessions or languages in the UI
 * while speaking or finalizing recognition never misroutes or mistranslates the utterance.
 */
data class RecordingSessionContext(
  val session: TestSession,
  val channel: ListeningChannel,
  val languageTag: String,
) {
  val speakerRole: String
    get() =
      if (channel == ListeningChannel.FOREIGN_TO_LOCAL) {
        "FOREIGN_ENGINEER"
      } else {
        "LOCAL_ENGINEER"
      }
}

data class VoiceBridgeState(
  val activeChannel: ListeningChannel = ListeningChannel.NONE,
  val isFinalizingSpeech: Boolean = false,
  val pinnedSessionId: String? = null,
  val partialTranscript: String = "",
  val rmsDbLevel: Float = 0f,
  val isTtsSpeaking: Boolean = false,
  val isTtsReady: Boolean = false,
  val lastSpokenText: String? = null,
  val lastSpokenLanguageTag: String? = null,
  val statusHint: String? = null,
)

class VoiceBridgeManager(
  private val context: Context,
  private val onFinalSpeechResult:
    (recordingContext: RecordingSessionContext, recognizedText: String) -> Unit,
  private val customSpeakHandler: ((text: String, languageTag: String) -> Unit)? = null,
) {
  private var speechRecognizer: SpeechRecognizer? = null
  private var tts: TextToSpeech? = null
  private var activeRecognitionListener: RecognitionListener? = null

  private val _state = MutableStateFlow(VoiceBridgeState())
  val state: StateFlow<VoiceBridgeState> = _state.asStateFlow()

  private var activeRecordingContext: RecordingSessionContext? = null

  init {
    initTextToSpeech()
  }

  private fun initTextToSpeech() {
    try {
      tts =
        TextToSpeech(context.applicationContext) { status ->
          if (status == TextToSpeech.SUCCESS) {
            tts?.setOnUtteranceProgressListener(
              object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                  _state.value = _state.value.copy(isTtsSpeaking = true)
                }

                override fun onDone(utteranceId: String?) {
                  _state.value = _state.value.copy(isTtsSpeaking = false)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                  _state.value = _state.value.copy(isTtsSpeaking = false)
                }
              }
            )
            _state.value = _state.value.copy(isTtsReady = true)
          } else {
            Log.w(TAG, "TextToSpeech initialization returned status: $status")
          }
        }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to initialize TextToSpeech", e)
    }
  }

  /**
   * Starts speech recognition and immediately pins the target [session] and [channel]
   * so any subsequent UI session switch before `onResults` arrives will not affect
   * where or how this utterance is recorded and translated.
   */
  fun startListening(
    channel: ListeningChannel,
    session: TestSession,
    forceEnableInTest: Boolean = false,
  ) {
    cancelListening()
    stopSpeaking()

    val languageTag =
      if (channel == ListeningChannel.FOREIGN_TO_LOCAL) {
        session.foreignLangCode
      } else {
        "zh-TW"
      }

    if (!forceEnableInTest && !SpeechRecognizer.isRecognitionAvailable(context)) {
      _state.value =
        _state.value.copy(
          activeChannel = ListeningChannel.NONE,
          isFinalizingSpeech = false,
          pinnedSessionId = null,
          statusHint = "此裝置／模擬器未安裝系統語音辨識服務，可直接點擊下方「現場常用語快選」或輸入文字進行即時雙向口譯。",
        )
      return
    }

    val pinnedContext =
      RecordingSessionContext(
        session = session,
        channel = channel,
        languageTag = languageTag,
      )
    activeRecordingContext = pinnedContext

    _state.value =
      _state.value.copy(
        activeChannel = channel,
        isFinalizingSpeech = false,
        pinnedSessionId = session.id,
        partialTranscript = "",
        rmsDbLevel = 0f,
        statusHint = null,
      )

    try {
      val listener = createRecognitionListener(pinnedContext)
      activeRecognitionListener = listener

      if (!forceEnableInTest) {
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        speechRecognizer = recognizer
        recognizer.setRecognitionListener(listener)

        val intent =
          Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
              RecognizerIntent.EXTRA_LANGUAGE_MODEL,
              RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
          }
        recognizer.startListening(intent)
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to start SpeechRecognizer", e)
      activeRecordingContext = null
      activeRecognitionListener = null
      _state.value =
        _state.value.copy(
          activeChannel = ListeningChannel.NONE,
          isFinalizingSpeech = false,
          pinnedSessionId = null,
          statusHint = "無法啟動語音收音，請使用下方捷運現場語句快選或文字輸入。",
        )
    }
  }

  private fun createRecognitionListener(pinnedContext: RecordingSessionContext): RecognitionListener {
    return object : RecognitionListener {
      override fun onReadyForSpeech(params: Bundle?) {
        _state.value = _state.value.copy(statusHint = "請開始說話，系統正在即時收音...")
      }

      override fun onBeginningOfSpeech() {}

      override fun onRmsChanged(rmsdB: Float) {
        _state.value = _state.value.copy(rmsDbLevel = rmsdB.coerceIn(0f, 12f))
      }

      override fun onBufferReceived(buffer: ByteArray?) {}

      override fun onEndOfSpeech() {
        _state.value =
          _state.value.copy(
            isFinalizingSpeech = true,
            rmsDbLevel = 0f,
            statusHint = "正在完成語音辨識...",
          )
      }

      override fun onError(error: Int) {
        val currentContext = activeRecordingContext ?: pinnedContext
        val fallbackPartial = _state.value.partialTranscript.trim()
        val wasFinalizing = _state.value.isFinalizingSpeech

        destroyRecognizerOnly()
        activeRecordingContext = null
        activeRecognitionListener = null

        // If the user already spoke partial text and tapped "完成收音" (or hit end-of-speech timeout),
        // preserve and emit the partial transcript instead of dropping their utterance!
        if (
          fallbackPartial.isNotBlank() &&
            (wasFinalizing ||
              error == SpeechRecognizer.ERROR_NO_MATCH ||
              error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
        ) {
          _state.value =
            _state.value.copy(
              activeChannel = ListeningChannel.NONE,
              isFinalizingSpeech = false,
              pinnedSessionId = null,
              partialTranscript = "",
              rmsDbLevel = 0f,
              statusHint = null,
            )
          onFinalSpeechResult(currentContext, fallbackPartial)
          return
        }

        val hint =
          when (error) {
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
              "未偵測到清晰語音。若在模擬器環境，可點擊下方「捷運現場情境語句」一鍵測試即時翻譯。"
            SpeechRecognizer.ERROR_AUDIO ->
              "麥克風音訊無法存取。您可使用下方「捷運現場情境語句」或文字輸入即時翻譯。"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
              "請允許麥克風權限以使用即時語音收音。"
            else ->
              "語音收音結束（代碼 $error），可直接點選下方現場語句或輸入文字。"
          }
        _state.value =
          _state.value.copy(
            activeChannel = ListeningChannel.NONE,
            isFinalizingSpeech = false,
            pinnedSessionId = null,
            partialTranscript = "",
            rmsDbLevel = 0f,
            statusHint = hint,
          )
        Log.w(TAG, "SpeechRecognizer error=$error on channel=${currentContext.channel}")
      }

      override fun onResults(results: Bundle?) {
        val currentContext = activeRecordingContext ?: pinnedContext
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val bestText =
          matches?.firstOrNull()?.trim().orEmpty().ifBlank {
            _state.value.partialTranscript.trim()
          }

        destroyRecognizerOnly()
        activeRecordingContext = null
        activeRecognitionListener = null

        _state.value =
          _state.value.copy(
            activeChannel = ListeningChannel.NONE,
            isFinalizingSpeech = false,
            pinnedSessionId = null,
            partialTranscript = "",
            rmsDbLevel = 0f,
            statusHint = null,
          )
        if (bestText.isNotBlank() && currentContext.channel != ListeningChannel.NONE) {
          onFinalSpeechResult(currentContext, bestText)
        }
      }

      override fun onPartialResults(partialResults: Bundle?) {
        val partial =
          partialResults
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()
        if (partial.isNotBlank()) {
          _state.value = _state.value.copy(partialTranscript = partial)
        }
      }

      override fun onEvent(eventType: Int, params: Bundle?) {}
    }
  }

  /**
   * Signals that the user has finished speaking (`SpeechRecognizer.stopListening()`)
   * WITHOUT cancelling or destroying the recognizer or clearing `activeRecordingContext`,
   * allowing Android's `SpeechRecognizer` to deliver `onResults` asynchronously.
   */
  fun finishListening() {
    if (activeRecordingContext == null) return
    _state.value =
      _state.value.copy(
        isFinalizingSpeech = true,
        rmsDbLevel = 0f,
        statusHint = "正在辨識剛才講完的語音...",
      )
    try {
      speechRecognizer?.stopListening()
    } catch (e: Exception) {
      Log.w(TAG, "stopListening failed", e)
    }
  }

  /**
   * Immediately aborts and discards the current speech recognition session without
   * emitting a final result. Use when the user explicitly cancels recording or on teardown.
   */
  fun cancelListening() {
    destroyRecognizerOnly()
    activeRecordingContext = null
    activeRecognitionListener = null
    _state.value =
      _state.value.copy(
        activeChannel = ListeningChannel.NONE,
        isFinalizingSpeech = false,
        pinnedSessionId = null,
        partialTranscript = "",
        rmsDbLevel = 0f,
      )
  }

  private fun destroyRecognizerOnly() {
    try {
      speechRecognizer?.cancel()
      speechRecognizer?.destroy()
    } catch (_: Exception) {}
    speechRecognizer = null
  }

  /**
   * Test helper to simulate `SpeechRecognizer` partial and final results so Robolectric
   * tests can verify `finishListening()` + delayed `onResults()` and session pinning.
   */
  internal fun deliverRecognitionResultsForTest(recognizedText: String) {
    val bundle =
      Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(recognizedText))
      }
    activeRecognitionListener?.onResults(bundle)
  }

  internal fun deliverPartialResultsForTest(partialText: String) {
    val bundle =
      Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(partialText))
      }
    activeRecognitionListener?.onPartialResults(bundle)
  }

  fun speakText(text: String, languageTag: String) {
    if (text.isBlank()) return
    _state.value =
      _state.value.copy(
        lastSpokenText = text,
        lastSpokenLanguageTag = languageTag,
      )
    if (customSpeakHandler != null) {
      customSpeakHandler.invoke(text, languageTag)
      return
    }
    val engine = tts ?: return
    try {
      val locale = Locale.forLanguageTag(languageTag)
      val langResult = engine.setLanguage(locale)
      if (
        langResult == TextToSpeech.LANG_MISSING_DATA ||
          langResult == TextToSpeech.LANG_NOT_SUPPORTED
      ) {
        Log.w(TAG, "TTS locale $languageTag not fully supported, using default fallback")
      }
      engine.setSpeechRate(0.98f)
      val utteranceId = UUID.randomUUID().toString()
      engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    } catch (e: Exception) {
      Log.e(TAG, "TTS speakText failed", e)
    }
  }

  fun stopSpeaking() {
    try {
      tts?.stop()
    } catch (_: Exception) {}
    _state.value = _state.value.copy(isTtsSpeaking = false)
  }

  fun clearHint() {
    _state.value = _state.value.copy(statusHint = null)
  }

  fun release() {
    cancelListening()
    try {
      tts?.stop()
      tts?.shutdown()
    } catch (_: Exception) {}
    tts = null
  }

  private companion object {
    const val TAG = "VoiceBridgeManager"
  }
}
