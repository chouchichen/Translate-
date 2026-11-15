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

data class VoiceBridgeState(
  val activeChannel: ListeningChannel = ListeningChannel.NONE,
  val partialTranscript: String = "",
  val rmsDbLevel: Float = 0f,
  val isTtsSpeaking: Boolean = false,
  val isTtsReady: Boolean = false,
  val statusHint: String? = null,
)

class VoiceBridgeManager(
  private val context: Context,
  private val onFinalSpeechResult: (channel: ListeningChannel, recognizedText: String) -> Unit,
) {
  private var speechRecognizer: SpeechRecognizer? = null
  private var tts: TextToSpeech? = null

  private val _state = MutableStateFlow(VoiceBridgeState())
  val state: StateFlow<VoiceBridgeState> = _state.asStateFlow()

  private var currentChannel: ListeningChannel = ListeningChannel.NONE

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

  fun startListening(channel: ListeningChannel, languageTag: String) {
    stopListening()
    stopSpeaking()

    if (!SpeechRecognizer.isRecognitionAvailable(context)) {
      _state.value =
        _state.value.copy(
          activeChannel = ListeningChannel.NONE,
          statusHint = "此裝置／模擬器未安裝系統語音辨識服務，可直接點擊下方「現場常用語快選」或輸入文字進行即時雙向口譯。",
        )
      return
    }

    currentChannel = channel
    _state.value =
      _state.value.copy(
        activeChannel = channel,
        partialTranscript = "",
        rmsDbLevel = 0f,
        statusHint = null,
      )

    try {
      val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
      speechRecognizer = recognizer
      recognizer.setRecognitionListener(
        object : RecognitionListener {
          override fun onReadyForSpeech(params: Bundle?) {
            _state.value = _state.value.copy(statusHint = "請開始說話，系統正在即時收音...")
          }

          override fun onBeginningOfSpeech() {}

          override fun onRmsChanged(rmsdB: Float) {
            _state.value = _state.value.copy(rmsDbLevel = rmsdB.coerceIn(0f, 12f))
          }

          override fun onBufferReceived(buffer: ByteArray?) {}

          override fun onEndOfSpeech() {
            _state.value = _state.value.copy(rmsDbLevel = 0f)
          }

          override fun onError(error: Int) {
            val active = currentChannel
            currentChannel = ListeningChannel.NONE
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
                partialTranscript = "",
                rmsDbLevel = 0f,
                statusHint = hint,
              )
            Log.w(TAG, "SpeechRecognizer error=$error on channel=$active")
          }

          override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val bestText = matches?.firstOrNull()?.trim().orEmpty()
            val finishedChannel = currentChannel
            currentChannel = ListeningChannel.NONE
            _state.value =
              _state.value.copy(
                activeChannel = ListeningChannel.NONE,
                partialTranscript = "",
                rmsDbLevel = 0f,
                statusHint = null,
              )
            if (bestText.isNotBlank() && finishedChannel != ListeningChannel.NONE) {
              onFinalSpeechResult(finishedChannel, bestText)
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
      )

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
    } catch (e: Exception) {
      Log.e(TAG, "Failed to start SpeechRecognizer", e)
      currentChannel = ListeningChannel.NONE
      _state.value =
        _state.value.copy(
          activeChannel = ListeningChannel.NONE,
          statusHint = "無法啟動語音收音，請使用下方捷運現場語句快選或文字輸入。",
        )
    }
  }

  fun stopListening() {
    try {
      speechRecognizer?.stopListening()
      speechRecognizer?.cancel()
      speechRecognizer?.destroy()
    } catch (_: Exception) {}
    speechRecognizer = null
    currentChannel = ListeningChannel.NONE
    _state.value =
      _state.value.copy(
        activeChannel = ListeningChannel.NONE,
        partialTranscript = "",
        rmsDbLevel = 0f,
      )
  }

  fun speakText(text: String, languageTag: String) {
    if (text.isBlank()) return
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
    stopListening()
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
