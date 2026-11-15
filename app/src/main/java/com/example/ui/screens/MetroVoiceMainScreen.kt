package com.example.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Subway
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.model.ForeignLanguageOption
import com.example.data.model.SUPPORTED_FOREIGN_LANGUAGES
import com.example.data.model.TEST_VERDICTS
import com.example.data.model.TestSession
import com.example.data.model.Utterance
import com.example.speech.ListeningChannel
import com.example.speech.RecordingSessionContext
import com.example.speech.VoiceBridgeManager
import com.example.ui.components.CreateTestSessionDialog
import com.example.ui.components.DualChannelControlDeck
import com.example.ui.components.FaceToFaceSpotlightDialog
import com.example.ui.components.UtteranceBilingualCard
import com.example.viewmodel.AiTaskState
import com.example.viewmodel.DataUiState
import com.example.viewmodel.MetroVoiceViewModel

@Composable
fun MetroVoiceMainScreen(
  viewModel: MetroVoiceViewModel,
  onSignOut: () -> Unit,
) {
  val context = LocalContext.current
  val sessionsState by viewModel.sessionsState.collectAsStateWithLifecycle()
  val utterancesState by viewModel.utterancesState.collectAsStateWithLifecycle()
  val selectedSessionId by viewModel.selectedSessionId.collectAsStateWithLifecycle()
  val aiTaskState by viewModel.aiTaskState.collectAsStateWithLifecycle()
  val autoSpeak by viewModel.autoSpeakTranslation.collectAsStateWithLifecycle()
  val spotlightUtterance by viewModel.spotlightUtterance.collectAsStateWithLifecycle()

  var activeTabIndex by remember { mutableIntStateOf(0) }
  var showCreateSessionDialog by remember { mutableStateOf(false) }
  var pendingMicRequest by remember { mutableStateOf<Pair<ListeningChannel, TestSession>?>(null) }

  BackHandler(enabled = activeTabIndex != 0) {
    activeTabIndex = 0
  }

  val sessions = (sessionsState as? DataUiState.Success)?.data.orEmpty()
  val currentSession =
    sessions.firstOrNull { it.id == selectedSessionId } ?: sessions.firstOrNull()
  val utterances = (utterancesState as? DataUiState.Success)?.data.orEmpty()

  val currentForeignLangOption =
    remember(currentSession?.foreignLangCode) {
      SUPPORTED_FOREIGN_LANGUAGES.firstOrNull { it.code == currentSession?.foreignLangCode }
        ?: SUPPORTED_FOREIGN_LANGUAGES.first()
    }

  val voiceBridge =
    remember(context) {
      lateinit var manager: VoiceBridgeManager
      manager =
        VoiceBridgeManager(
          context = context,
          onFinalSpeechResult = { recordingContext: RecordingSessionContext, recognizedText: String ->
            // Uses the exact session and speaker direction pinned when recording started,
            // AND invokes manager.speakText(...) when translation completes!
            viewModel.translateAndRecordUtterance(
              session = recordingContext.session,
              speakerRole = recordingContext.speakerRole,
              spokenText = recognizedText,
              onSpeakTranslationReady = { spokenTranslation, langTag ->
                manager.speakText(spokenTranslation, langTag)
              },
            )
          },
        )
      manager
    }

  val voiceState by voiceBridge.state.collectAsStateWithLifecycle()

  DisposableEffect(voiceBridge) {
    onDispose { voiceBridge.release() }
  }

  val micPermissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
      val request = pendingMicRequest
      pendingMicRequest = null
      if (isGranted && request != null) {
        val (targetChannel, pinnedSession) = request
        voiceBridge.startListening(targetChannel, pinnedSession)
      } else if (!isGranted) {
        Toast.makeText(
            context,
            "未授予麥克風權限，您仍可點選現場常用語快選或輸入文字進行即時口譯。",
            Toast.LENGTH_LONG,
          )
          .show()
      }
    }

  fun triggerVoiceChannel(channel: ListeningChannel) {
    val sessionToPin = currentSession ?: return
    val hasMicPerm =
      ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
    if (hasMicPerm) {
      voiceBridge.startListening(channel, sessionToPin)
    } else {
      pendingMicRequest = channel to sessionToPin
      micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
  }

  if (spotlightUtterance != null) {
    FaceToFaceSpotlightDialog(
      utterance = spotlightUtterance!!,
      onSpeakText = { text, lang -> voiceBridge.speakText(text, lang) },
      onDismiss = { viewModel.showSpotlightCard(null) },
    )
  }

  if (showCreateSessionDialog) {
    CreateTestSessionDialog(
      onDismiss = { showCreateSessionDialog = false },
      onCreateSession = { title, line, category, lang ->
        viewModel.createNewSession(title, line, category, lang)
        activeTabIndex = 0
      },
    )
  }

  Scaffold(
    contentWindowInsets = WindowInsets.safeDrawing,
    topBar = {
      MetroTopTelemetryBar(
        currentSession = currentSession,
        foreignLang = currentForeignLangOption,
        autoSpeak = autoSpeak,
        onToggleAutoSpeak = { viewModel.toggleAutoSpeak() },
        onSelectLanguage = { newLang ->
          if (currentSession != null) {
            viewModel.changeSessionLanguage(currentSession, newLang)
          }
        },
        onOpenNewSession = { showCreateSessionDialog = true },
        onQuickGenerateMinutes = {
          if (currentSession != null) {
            viewModel.generateMeetingMinutes(
              session = currentSession,
              utterances = utterances,
              onMinutesGenerated = { activeTabIndex = 1 },
            )
          }
        },
        onSignOut = onSignOut,
      )
    },
    bottomBar = {
      NavigationBar {
        NavigationBarItem(
          selected = activeTabIndex == 0,
          onClick = { activeTabIndex = 0 },
          icon = { Icon(Icons.Default.Translate, contentDescription = null) },
          label = { Text(stringResource(R.string.tab_live_interpreter)) },
          modifier = Modifier.testTag("nav_tab_interpreter"),
        )
        NavigationBarItem(
          selected = activeTabIndex == 1,
          onClick = { activeTabIndex = 1 },
          icon = { Icon(Icons.Default.Description, contentDescription = null) },
          label = {
            val countBadge =
              if (currentSession?.minutesKeyPoints?.isNotEmpty() == true) " (已產出)" else ""
            Text(stringResource(R.string.tab_meeting_minutes) + countBadge)
          },
          modifier = Modifier.testTag("nav_tab_minutes"),
        )
        NavigationBarItem(
          selected = activeTabIndex == 2,
          onClick = { activeTabIndex = 2 },
          icon = { Icon(Icons.Default.FolderSpecial, contentDescription = null) },
          label = { Text(stringResource(R.string.tab_sessions_glossary)) },
          modifier = Modifier.testTag("nav_tab_sessions"),
        )
      }
    },
  ) { innerPadding ->
    BoxWithConstraints(
      modifier = Modifier.fillMaxSize().padding(innerPadding)
    ) {
      val isWideScreen = maxWidth >= 760.dp

      Column(modifier = Modifier.fillMaxSize()) {
        // Error or Voice Hint Banner
        if (aiTaskState is AiTaskState.Error) {
          val msg = (aiTaskState as AiTaskState.Error).message
          Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            shape = RoundedCornerShape(12.dp),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth().padding(12.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              Text(
                text = msg,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
              )
              TextButton(onClick = { viewModel.clearAiError() }) {
                Text("關閉")
              }
            }
          }
        }

        if (voiceState.statusHint != null) {
          Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            shape = RoundedCornerShape(12.dp),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              Text(
                text = voiceState.statusHint ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
              )
              TextButton(onClick = { voiceBridge.clearHint() }) {
                Text("知道了")
              }
            }
          }
        }

        when {
          sessionsState is DataUiState.Loading && currentSession == null -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
              Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(12.dp))
                Text("正在載入捷運測試雲端工作區...")
              }
            }
          }

          currentSession == null -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
              Button(onClick = { showCreateSessionDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("建立第一個捷運測試口譯場次")
              }
            }
          }

          isWideScreen && activeTabIndex != 2 -> {
            Row(
              modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
              horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
              Box(modifier = Modifier.weight(1.1f).fillMaxHeight()) {
                LiveInterpreterPane(
                  session = currentSession,
                  foreignLang = currentForeignLangOption,
                  utterances = utterances,
                  activeListeningChannel = voiceState.activeChannel,
                  isFinalizingSpeech = voiceState.isFinalizingSpeech,
                  partialSpeechText = voiceState.partialTranscript,
                  aiTaskState = aiTaskState,
                  onStartVoiceChannel = { triggerVoiceChannel(it) },
                  onFinishVoiceChannel = { voiceBridge.finishListening() },
                  onCancelVoiceChannel = { voiceBridge.cancelListening() },
                  onSubmitStatement = { role, text ->
                    viewModel.translateAndRecordUtterance(
                      session = currentSession,
                      speakerRole = role,
                      spokenText = text,
                      onSpeakTranslationReady = { spokenTranslation, langTag ->
                        voiceBridge.speakText(spokenTranslation, langTag)
                      },
                    )
                  },
                  onSpeakText = { text, lang -> voiceBridge.speakText(text, lang) },
                  onTogglePin = { viewModel.togglePinUtterance(it) },
                  onOpenSpotlight = { viewModel.showSpotlightCard(it) },
                  onRetryTranslation = { utterance ->
                    val targetSession =
                      sessions.firstOrNull { it.id == utterance.sessionId } ?: currentSession
                    viewModel.retryTranslateUtterance(
                      session = targetSession,
                      utterance = utterance,
                      onSpeakTranslationReady = { spokenTranslation, langTag ->
                        voiceBridge.speakText(spokenTranslation, langTag)
                      },
                    )
                  },
                  onGenerateMinutesNow = {
                    viewModel.generateMeetingMinutes(
                      session = currentSession,
                      utterances = utterances,
                    )
                  },
                )
              }
              Box(modifier = Modifier.weight(0.9f).fillMaxHeight()) {
                MeetingMinutesPane(
                  session = currentSession,
                  utterances = utterances,
                  aiTaskState = aiTaskState,
                  onGenerateMinutes = {
                    viewModel.generateMeetingMinutes(
                      session = currentSession,
                      utterances = utterances,
                    )
                  },
                  onUpdateVerdict = { verdict ->
                    viewModel.updateVerdictManually(currentSession, verdict)
                  },
                  onReadSummaryAloud = {
                    voiceBridge.speakText(currentSession.minutesSummary, "zh-TW")
                  },
                  onCopyReport = {
                    val report = viewModel.buildFormattedMinutesReport(currentSession, utterances)
                    copyReportToClipboard(context, report)
                  },
                  onShareReport = {
                    val report = viewModel.buildFormattedMinutesReport(currentSession, utterances)
                    shareReportIntent(context, currentSession.title, report)
                  },
                )
              }
            }
          }

          activeTabIndex == 0 -> {
            LiveInterpreterPane(
              session = currentSession,
              foreignLang = currentForeignLangOption,
              utterances = utterances,
              activeListeningChannel = voiceState.activeChannel,
              isFinalizingSpeech = voiceState.isFinalizingSpeech,
              partialSpeechText = voiceState.partialTranscript,
              aiTaskState = aiTaskState,
              onStartVoiceChannel = { triggerVoiceChannel(it) },
              onFinishVoiceChannel = { voiceBridge.finishListening() },
              onCancelVoiceChannel = { voiceBridge.cancelListening() },
              onSubmitStatement = { role, text ->
                viewModel.translateAndRecordUtterance(
                  session = currentSession,
                  speakerRole = role,
                  spokenText = text,
                  onSpeakTranslationReady = { spokenTranslation, langTag ->
                    voiceBridge.speakText(spokenTranslation, langTag)
                  },
                )
              },
              onSpeakText = { text, lang -> voiceBridge.speakText(text, lang) },
              onTogglePin = { viewModel.togglePinUtterance(it) },
              onOpenSpotlight = { viewModel.showSpotlightCard(it) },
              onRetryTranslation = { utterance ->
                val targetSession =
                  sessions.firstOrNull { it.id == utterance.sessionId } ?: currentSession
                viewModel.retryTranslateUtterance(
                  session = targetSession,
                  utterance = utterance,
                  onSpeakTranslationReady = { spokenTranslation, langTag ->
                    voiceBridge.speakText(spokenTranslation, langTag)
                  },
                )
              },
              onGenerateMinutesNow = {
                viewModel.generateMeetingMinutes(
                  session = currentSession,
                  utterances = utterances,
                  onMinutesGenerated = { activeTabIndex = 1 },
                )
              },
              modifier = Modifier.padding(horizontal = 14.dp),
            )
          }

          activeTabIndex == 1 -> {
            MeetingMinutesPane(
              session = currentSession,
              utterances = utterances,
              aiTaskState = aiTaskState,
              onGenerateMinutes = {
                viewModel.generateMeetingMinutes(
                  session = currentSession,
                  utterances = utterances,
                )
              },
              onUpdateVerdict = { verdict ->
                viewModel.updateVerdictManually(currentSession, verdict)
              },
              onReadSummaryAloud = {
                voiceBridge.speakText(currentSession.minutesSummary, "zh-TW")
              },
              onCopyReport = {
                val report = viewModel.buildFormattedMinutesReport(currentSession, utterances)
                copyReportToClipboard(context, report)
              },
              onShareReport = {
                val report = viewModel.buildFormattedMinutesReport(currentSession, utterances)
                shareReportIntent(context, currentSession.title, report)
              },
              modifier = Modifier.padding(horizontal = 14.dp),
            )
          }

          else -> {
            SessionsAndGlossaryPane(
              sessions = sessions,
              selectedSessionId = currentSession.id,
              currentForeignLang = currentForeignLangOption,
              onSelectSession = {
                viewModel.selectSession(it)
                activeTabIndex = 0
              },
              onDeleteSession = { viewModel.deleteSession(it) },
              onOpenNewSession = { showCreateSessionDialog = true },
              onUseQuickPhrase = { role, phrase ->
                viewModel.translateAndRecordUtterance(
                  session = currentSession,
                  speakerRole = role,
                  spokenText = phrase,
                  onSpeakTranslationReady = { spokenTranslation, langTag ->
                    voiceBridge.speakText(spokenTranslation, langTag)
                  },
                )
                activeTabIndex = 0
              },
              modifier = Modifier.padding(horizontal = 14.dp),
            )
          }
        }
      }
    }
  }
}

@Composable
private fun MetroTopTelemetryBar(
  currentSession: TestSession?,
  foreignLang: ForeignLanguageOption,
  autoSpeak: Boolean,
  onToggleAutoSpeak: () -> Unit,
  onSelectLanguage: (ForeignLanguageOption) -> Unit,
  onOpenNewSession: () -> Unit,
  onQuickGenerateMinutes: () -> Unit,
  onSignOut: () -> Unit,
) {
  Surface(
    color = MaterialTheme.colorScheme.surface,
    tonalElevation = 4.dp,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
          Box(
            modifier =
              Modifier.size(38.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
          ) {
            Icon(
              imageVector = Icons.Default.Subway,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(22.dp),
            )
          }
          Spacer(modifier = Modifier.width(10.dp))
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = currentSession?.title ?: "MetroVoice 捷運測試雙向口譯",
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            Text(
              text =
                if (currentSession != null) {
                  "${currentSession.metroLine} ・ ${currentSession.testCategory}"
                } else {
                  "即時外語口譯 ⇄ 中文反譯 ⇄ 會議紀錄"
                },
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
          IconButton(
            onClick = onToggleAutoSpeak,
            modifier = Modifier.testTag("toggle_auto_speak_btn"),
          ) {
            Icon(
              imageVector =
                if (autoSpeak) {
                  Icons.AutoMirrored.Filled.VolumeUp
                } else {
                  Icons.AutoMirrored.Filled.VolumeOff
                },
              contentDescription = if (autoSpeak) "自動語音朗讀：開啟" else "自動語音朗讀：關閉",
              tint =
                if (autoSpeak) {
                  MaterialTheme.colorScheme.primary
                } else {
                  MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
          }
          IconButton(
            onClick = onOpenNewSession,
            modifier = Modifier.testTag("top_new_session_btn"),
          ) {
            Icon(
              imageVector = Icons.Default.Add,
              contentDescription = stringResource(R.string.btn_new_session),
              tint = MaterialTheme.colorScheme.primary,
            )
          }
          IconButton(
            onClick = onSignOut,
            modifier = Modifier.testTag("sign_out_btn"),
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.ExitToApp,
              contentDescription = stringResource(R.string.btn_sign_out),
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }

      Spacer(modifier = Modifier.height(8.dp))

      // Language Switcher + Instant Generate Meeting Minutes Action
      Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        SUPPORTED_FOREIGN_LANGUAGES.forEach { lang ->
          val selected = lang.code == foreignLang.code
          FilterChip(
            selected = selected,
            onClick = { onSelectLanguage(lang) },
            label = { Text("${lang.shortTag} ${lang.label.substringBefore(" ")}") },
            colors =
              FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                selectedLabelColor = MaterialTheme.colorScheme.primary,
              ),
            modifier = Modifier.testTag("lang_chip_${lang.shortTag}"),
          )
        }

        Button(
          onClick = onQuickGenerateMinutes,
          shape = RoundedCornerShape(10.dp),
          contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
          colors =
            ButtonDefaults.buttonColors(
              containerColor = MaterialTheme.colorScheme.tertiary,
              contentColor = MaterialTheme.colorScheme.onTertiary,
            ),
          modifier = Modifier.height(34.dp).testTag("top_quick_minutes_btn"),
        ) {
          Icon(
            imageVector = Icons.Default.AutoAwesome,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text("即時產出會議紀錄", style = MaterialTheme.typography.labelLarge)
        }
      }
    }
  }
}

@Composable
private fun LiveInterpreterPane(
  session: TestSession,
  foreignLang: ForeignLanguageOption,
  utterances: List<Utterance>,
  activeListeningChannel: ListeningChannel,
  isFinalizingSpeech: Boolean,
  partialSpeechText: String,
  aiTaskState: AiTaskState,
  onStartVoiceChannel: (ListeningChannel) -> Unit,
  onFinishVoiceChannel: () -> Unit,
  onCancelVoiceChannel: () -> Unit,
  onSubmitStatement: (speakerRole: String, text: String) -> Unit,
  onSpeakText: (text: String, lang: String) -> Unit,
  onTogglePin: (Utterance) -> Unit,
  onOpenSpotlight: (Utterance) -> Unit,
  onRetryTranslation: (Utterance) -> Unit,
  onGenerateMinutesNow: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val isTranslating = aiTaskState is AiTaskState.Translating
  val translatingPreview = (aiTaskState as? AiTaskState.Translating)?.previewText.orEmpty()

  LazyColumn(
    modifier = modifier.fillMaxSize(),
    contentPadding = PaddingValues(vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item {
      DualChannelControlDeck(
        foreignLang = foreignLang,
        activeListeningChannel = activeListeningChannel,
        isFinalizingSpeech = isFinalizingSpeech,
        partialSpeechText = partialSpeechText,
        isAiTranslating = isTranslating,
        translatingPreview = translatingPreview,
        onStartVoiceChannel = onStartVoiceChannel,
        onFinishVoiceChannel = onFinishVoiceChannel,
        onCancelVoiceChannel = onCancelVoiceChannel,
        onSubmitStatement = onSubmitStatement,
      )
    }

    item {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(
          text = "雙向口譯對話紀錄 (${utterances.size} 筆)",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
        )
        TextButton(
          onClick = onGenerateMinutesNow,
          enabled = utterances.isNotEmpty() && aiTaskState !is AiTaskState.GeneratingMinutes,
          modifier = Modifier.testTag("transcript_generate_minutes_btn"),
        ) {
          Icon(
            imageVector = Icons.Default.AutoAwesome,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text("彙整為會議紀錄")
        }
      }
    }

    if (utterances.isEmpty()) {
      item {
        Card(
          shape = RoundedCornerShape(18.dp),
          colors =
            CardDefaults.cardColors(
              containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            ),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
          ) {
            Icon(
              imageVector = Icons.Default.Translate,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(40.dp),
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
              text = stringResource(R.string.empty_utterances_hint),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    } else {
      items(items = utterances.reversed(), key = { it.id }) { utterance ->
        UtteranceBilingualCard(
          utterance = utterance,
          onSpeakText = onSpeakText,
          onTogglePin = onTogglePin,
          onOpenSpotlight = onOpenSpotlight,
          onRetryTranslation = onRetryTranslation,
        )
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MeetingMinutesPane(
  session: TestSession,
  utterances: List<Utterance>,
  aiTaskState: AiTaskState,
  onGenerateMinutes: () -> Unit,
  onUpdateVerdict: (String) -> Unit,
  onReadSummaryAloud: () -> Unit,
  onCopyReport: () -> Unit,
  onShareReport: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val isGenerating = aiTaskState is AiTaskState.GeneratingMinutes
  val pinnedCount = utterances.count { it.isPinnedToMinutes }

  LazyColumn(
    modifier = modifier.fillMaxSize(),
    contentPadding = PaddingValues(vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    // Header Action Card
    item {
      Card(
        shape = RoundedCornerShape(20.dp),
        colors =
          CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
          ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = "捷運測試現場會議紀錄",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
              )
              Text(
                text = "已收錄 ${utterances.size} 筆雙向口譯對話（含 $pinnedCount 筆★重點標記）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }

          Spacer(modifier = Modifier.height(12.dp))

          Button(
            onClick = onGenerateMinutes,
            enabled = !isGenerating && utterances.isNotEmpty(),
            shape = RoundedCornerShape(14.dp),
            colors =
              ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.tertiary,
                contentColor = MaterialTheme.colorScheme.onTertiary,
              ),
            modifier = Modifier.fillMaxWidth().height(52.dp).testTag("generate_minutes_main_btn"),
          ) {
            if (isGenerating) {
              CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                color = MaterialTheme.colorScheme.onTertiary,
                strokeWidth = 2.5.dp,
              )
              Spacer(modifier = Modifier.width(10.dp))
              Text("AI 正在彙整雙語測試會議紀錄...", fontWeight = FontWeight.Bold)
            } else {
              Icon(Icons.Default.AutoAwesome, contentDescription = null)
              Spacer(modifier = Modifier.width(8.dp))
              Text(
                text = stringResource(R.string.btn_generate_minutes),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
              )
            }
          }

          Spacer(modifier = Modifier.height(10.dp))

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            OutlinedButton(
              onClick = onReadSummaryAloud,
              modifier = Modifier.weight(1f).testTag("read_minutes_aloud_btn"),
            ) {
              Icon(
                Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
              )
              Spacer(modifier = Modifier.width(6.dp))
              Text("朗讀摘要")
            }
            OutlinedButton(
              onClick = onCopyReport,
              modifier = Modifier.weight(1f).testTag("copy_minutes_btn"),
            ) {
              Icon(
                Icons.Default.ContentCopy,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
              )
              Spacer(modifier = Modifier.width(6.dp))
              Text("複製全文")
            }
            OutlinedButton(
              onClick = onShareReport,
              modifier = Modifier.weight(1f).testTag("share_minutes_btn"),
            ) {
              Icon(
                Icons.Default.Share,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
              )
              Spacer(modifier = Modifier.width(6.dp))
              Text("匯出分享")
            }
          }
        }
      }
    }

    // Test Verdict Selector Card
    item {
      Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
          Text(
            text = "測試判定狀態 (Test Verdict)",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
          )
          Spacer(modifier = Modifier.height(8.dp))
          FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            TEST_VERDICTS.forEach { verdict ->
              FilterChip(
                selected = session.minutesTestVerdict == verdict,
                onClick = { onUpdateVerdict(verdict) },
                label = { Text(verdict) },
                leadingIcon =
                  if (session.minutesTestVerdict == verdict) {
                    {
                      Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                      )
                    }
                  } else {
                    null
                  },
              )
            }
          }
        }
      }
    }

    // Section 1: Bilingual Executive Summary
    item {
      Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
          Text(
            text = "一、執行摘要 (Executive Summary)",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
          )
          Spacer(modifier = Modifier.height(8.dp))
          Text(
            text = session.minutesSummary,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
          )
        }
      }
    }

    // Section 2: Key Technical Findings
    item {
      Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
          Text(
            text = "二、關鍵測試發現與技術數據 (Key Technical Findings)",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
          )
          Spacer(modifier = Modifier.height(8.dp))
          if (session.minutesKeyPoints.isEmpty()) {
            Text(
              text = "點擊上方「AI 即時產出／更新會議紀錄」，系統將自動從雙向對話中擷取測試數據與技術結論。",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          } else {
            session.minutesKeyPoints.forEachIndexed { idx, point ->
              Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.Top,
              ) {
                Surface(
                  shape = CircleShape,
                  color = MaterialTheme.colorScheme.primaryContainer,
                  modifier = Modifier.size(24.dp),
                ) {
                  Box(contentAlignment = Alignment.Center) {
                    Text(
                      text = "${idx + 1}",
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.primary,
                      fontWeight = FontWeight.Bold,
                    )
                  }
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                  text = point,
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurface,
                  modifier = Modifier.weight(1f),
                )
              }
            }
          }
        }
      }
    }

    // Section 3: Action Items
    item {
      Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
          Text(
            text = "三、待辦與追蹤改善事項 (Action Items)",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.secondary,
          )
          Spacer(modifier = Modifier.height(8.dp))
          if (session.minutesActionItems.isEmpty()) {
            Text(
              text = "產出會議紀錄後，此處將列出外籍技師與我方工程師的後續待辦追蹤事項。",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          } else {
            session.minutesActionItems.forEachIndexed { idx, item ->
              Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.Top,
              ) {
                Surface(
                  shape = RoundedCornerShape(6.dp),
                  color = MaterialTheme.colorScheme.secondaryContainer,
                  modifier = Modifier.padding(top = 2.dp),
                ) {
                  Text(
                    text = "TODO ${idx + 1}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                  )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                  text = item,
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurface,
                  modifier = Modifier.weight(1f),
                )
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun SessionsAndGlossaryPane(
  sessions: List<TestSession>,
  selectedSessionId: String,
  currentForeignLang: ForeignLanguageOption,
  onSelectSession: (String) -> Unit,
  onDeleteSession: (String) -> Unit,
  onOpenNewSession: () -> Unit,
  onUseQuickPhrase: (speakerRole: String, phrase: String) -> Unit,
  modifier: Modifier = Modifier,
) {
  LazyColumn(
    modifier = modifier.fillMaxSize(),
    contentPadding = PaddingValues(vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = "捷運測試會議場次 (${sessions.size})",
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.Bold,
        )
        Button(
          onClick = onOpenNewSession,
          modifier = Modifier.testTag("sessions_tab_new_btn"),
        ) {
          Icon(Icons.Default.Add, contentDescription = null)
          Spacer(modifier = Modifier.width(6.dp))
          Text(stringResource(R.string.btn_new_session))
        }
      }
    }

    items(items = sessions, key = { it.id }) { session ->
      val isSelected = session.id == selectedSessionId
      Card(
        shape = RoundedCornerShape(16.dp),
        colors =
          CardDefaults.cardColors(
            containerColor =
              if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
              } else {
                MaterialTheme.colorScheme.surface
              }
          ),
        border =
          BorderStroke(
            width = if (isSelected) 2.dp else 1.dp,
            color =
              if (isSelected) {
                MaterialTheme.colorScheme.primary
              } else {
                MaterialTheme.colorScheme.surfaceVariant
              },
          ),
        modifier =
          Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { onSelectSession(session.id) }
            .testTag("session_item_${session.id}"),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(14.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Surface(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                shape = RoundedCornerShape(6.dp),
              ) {
                Text(
                  text = session.metroLine,
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.primary,
                  modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
              }
              Spacer(modifier = Modifier.width(8.dp))
              Text(
                text = session.foreignLangLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
              )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
              text = session.title,
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold,
            )
            Text(
              text = "${session.testCategory} ・ 判定：${session.minutesTestVerdict} ・ ${session.utteranceCount} 筆對話",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          if (sessions.size > 1) {
            IconButton(onClick = { onDeleteSession(session.id) }) {
              Icon(
                imageVector = Icons.Default.DeleteOutline,
                contentDescription = "刪除場次",
                tint = MaterialTheme.colorScheme.error,
              )
            }
          }
        }
      }
    }

    item {
      Spacer(modifier = Modifier.height(8.dp))
      HorizontalDivider()
      Spacer(modifier = Modifier.height(8.dp))
      Text(
        text = "捷運測試現場常用雙向語料庫 (${currentForeignLang.label})",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
      )
      Text(
        text = "點擊任一語句即可直接送入目前場次進行 AI 雙向翻譯與朗讀：",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    items(currentForeignLang.sampleLocalPhrases) { phrase ->
      Card(
        shape = RoundedCornerShape(14.dp),
        colors =
          CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)
          ),
        modifier =
          Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { onUseQuickPhrase("LOCAL_ENGINEER", phrase) },
      ) {
        Column(modifier = Modifier.padding(12.dp)) {
          Text(
            text = "我方中文指令 (點擊反向翻譯為 ${currentForeignLang.shortTag} 並朗讀)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary,
          )
          Spacer(modifier = Modifier.height(4.dp))
          Text(text = phrase, style = MaterialTheme.typography.bodyMedium)
        }
      }
    }

    items(currentForeignLang.sampleForeignPhrases) { phrase ->
      Card(
        shape = RoundedCornerShape(14.dp),
        colors =
          CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
          ),
        modifier =
          Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { onUseQuickPhrase("FOREIGN_ENGINEER", phrase) },
      ) {
        Column(modifier = Modifier.padding(12.dp)) {
          Text(
            text = "外籍技師發言範例 (點擊即時翻譯為繁體中文)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
          )
          Spacer(modifier = Modifier.height(4.dp))
          Text(text = phrase, style = MaterialTheme.typography.bodyMedium)
        }
      }
    }
  }
}

private fun copyReportToClipboard(context: Context, report: String) {
  val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
  clipboard?.setPrimaryClip(ClipData.newPlainText("MetroVoice 會議紀錄", report))
  Toast.makeText(context, "已複製完整雙語會議紀錄至剪貼簿！", Toast.LENGTH_SHORT).show()
}

private fun shareReportIntent(context: Context, title: String, report: String) {
  val sendIntent =
    Intent(Intent.ACTION_SEND).apply {
      type = "text/plain"
      putExtra(Intent.EXTRA_SUBJECT, "【捷運測試會議紀錄】$title")
      putExtra(Intent.EXTRA_TEXT, report)
    }
  context.startActivity(Intent.createChooser(sendIntent, "分享捷運測試會議紀錄"))
}
