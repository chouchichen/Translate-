package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.model.ForeignLanguageOption
import com.example.data.model.METRO_LINES
import com.example.data.model.SUPPORTED_FOREIGN_LANGUAGES
import com.example.data.model.TEST_CATEGORIES
import com.example.data.model.Utterance
import com.example.speech.ListeningChannel
import com.example.viewmodel.canRetryTranslation
import com.example.viewmodel.isTranslationFailed
import com.example.viewmodel.isTranslationPending
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun DualChannelControlDeck(
  foreignLang: ForeignLanguageOption,
  activeListeningChannel: ListeningChannel,
  isFinalizingSpeech: Boolean,
  partialSpeechText: String,
  isAiTranslating: Boolean,
  translatingPreview: String,
  onStartVoiceChannel: (ListeningChannel) -> Unit,
  onFinishVoiceChannel: () -> Unit,
  onCancelVoiceChannel: () -> Unit,
  onSubmitStatement: (speakerRole: String, text: String) -> Unit,
) {
  var selectedInputChannel by remember { mutableStateOf("FOREIGN_ENGINEER") }
  var manualInputText by remember { mutableStateOf("") }
  var showQuickPhrases by remember { mutableStateOf(true) }

  val isForeignListening = activeListeningChannel == ListeningChannel.FOREIGN_TO_LOCAL
  val isLocalListening = activeListeningChannel == ListeningChannel.LOCAL_TO_FOREIGN

  val infiniteTransition = rememberInfiniteTransition(label = "mic_pulse")
  val pulseScale by
    infiniteTransition.animateFloat(
      initialValue = 1f,
      targetValue = 1.07f,
      animationSpec =
        infiniteRepeatable(
          animation = tween(650),
          repeatMode = RepeatMode.Reverse,
        ),
      label = "pulse_scale",
    )

  Card(
    shape = RoundedCornerShape(22.dp),
    colors =
      CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surface,
      ),
    border =
      BorderStroke(
        width = 1.dp,
        color = MaterialTheme.colorScheme.surfaceVariant,
      ),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
      // Two Big Bidirectional Action Cards: Foreign -> Local (Cyan) & Local -> Foreign (Amber)
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        // Channel A: Foreign Engineer Speaking -> Real-time Chinese
        Surface(
          shape = RoundedCornerShape(18.dp),
          color =
            if (isForeignListening) {
              MaterialTheme.colorScheme.primary
            } else {
              MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f)
            },
          border =
            BorderStroke(
              width = if (selectedInputChannel == "FOREIGN_ENGINEER") 2.dp else 1.dp,
              color = MaterialTheme.colorScheme.primary,
            ),
          modifier =
            Modifier.weight(1f)
              .scale(if (isForeignListening && !isFinalizingSpeech) pulseScale else 1f)
              .clip(RoundedCornerShape(18.dp))
              .clickable {
                selectedInputChannel = "FOREIGN_ENGINEER"
                if (isForeignListening) {
                  onFinishVoiceChannel()
                } else {
                  onStartVoiceChannel(ListeningChannel.FOREIGN_TO_LOCAL)
                }
              }
              .testTag("foreign_speaker_mic_btn"),
        ) {
          Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.Center,
            ) {
              Icon(
                imageVector = if (isForeignListening) Icons.Default.Check else Icons.Default.Mic,
                contentDescription = "外籍技師語音收音",
                tint =
                  if (isForeignListening) {
                    MaterialTheme.colorScheme.onPrimary
                  } else {
                    MaterialTheme.colorScheme.primary
                  },
                modifier = Modifier.size(22.dp),
              )
              Spacer(modifier = Modifier.width(6.dp))
              Text(
                text = "${foreignLang.shortTag} -> 中文",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color =
                  if (isForeignListening) {
                    MaterialTheme.colorScheme.onPrimary
                  } else {
                    MaterialTheme.colorScheme.primary
                  },
              )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
              text =
                when {
                  isForeignListening && isFinalizingSpeech -> "正在完成辨識..."
                  isForeignListening -> "講完送出辨識"
                  else -> "外籍技師講話"
                },
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold,
              color =
                if (isForeignListening) {
                  MaterialTheme.colorScheme.onPrimary
                } else {
                  MaterialTheme.colorScheme.onPrimaryContainer
                },
            )
            Text(
              text = "即時聽懂外語並翻成繁中",
              style = MaterialTheme.typography.bodySmall,
              color =
                if (isForeignListening) {
                  MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
                } else {
                  MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                },
              textAlign = TextAlign.Center,
            )
          }
        }

        // Channel B: Local MRT Engineer Speaking -> Reverse Translate to Foreign & Speak Aloud
        Surface(
          shape = RoundedCornerShape(18.dp),
          color =
            if (isLocalListening) {
              MaterialTheme.colorScheme.secondary
            } else {
              MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.85f)
            },
          border =
            BorderStroke(
              width = if (selectedInputChannel == "LOCAL_ENGINEER") 2.dp else 1.dp,
              color = MaterialTheme.colorScheme.secondary,
            ),
          modifier =
            Modifier.weight(1f)
              .scale(if (isLocalListening && !isFinalizingSpeech) pulseScale else 1f)
              .clip(RoundedCornerShape(18.dp))
              .clickable {
                selectedInputChannel = "LOCAL_ENGINEER"
                if (isLocalListening) {
                  onFinishVoiceChannel()
                } else {
                  onStartVoiceChannel(ListeningChannel.LOCAL_TO_FOREIGN)
                }
              }
              .testTag("local_speaker_mic_btn"),
        ) {
          Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.Center,
            ) {
              Icon(
                imageVector =
                  if (isLocalListening) Icons.Default.Check else Icons.Default.RecordVoiceOver,
                contentDescription = "我方工程師語音收音",
                tint =
                  if (isLocalListening) {
                    MaterialTheme.colorScheme.onSecondary
                  } else {
                    MaterialTheme.colorScheme.secondary
                  },
                modifier = Modifier.size(22.dp),
              )
              Spacer(modifier = Modifier.width(6.dp))
              Text(
                text = "中文 -> ${foreignLang.shortTag}",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color =
                  if (isLocalListening) {
                    MaterialTheme.colorScheme.onSecondary
                  } else {
                    MaterialTheme.colorScheme.secondary
                  },
              )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
              text =
                when {
                  isLocalListening && isFinalizingSpeech -> "正在完成辨識..."
                  isLocalListening -> "講完送出辨識"
                  else -> "我方講話反譯"
                },
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold,
              color =
                if (isLocalListening) {
                  MaterialTheme.colorScheme.onSecondary
                } else {
                  MaterialTheme.colorScheme.onSecondaryContainer
                },
            )
            Text(
              text = "反向翻成外語給對方聽",
              style = MaterialTheme.typography.bodySmall,
              color =
                if (isLocalListening) {
                  MaterialTheme.colorScheme.onSecondary.copy(alpha = 0.85f)
                } else {
                  MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                },
              textAlign = TextAlign.Center,
            )
          }
        }
      }

      // Live Speech / AI Translation Status Banner with Separate Finish vs Cancel Controls
      AnimatedVisibility(
        visible = activeListeningChannel != ListeningChannel.NONE || isAiTranslating
      ) {
        Surface(
          shape = RoundedCornerShape(12.dp),
          color = MaterialTheme.colorScheme.surfaceVariant,
          modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Icon(
              imageVector = Icons.Default.GraphicEq,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(22.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text =
                  when {
                    isAiTranslating -> "Gemini AI 正在進行軌道專業雙向口譯..."
                    isFinalizingSpeech -> "正在完成語音辨識並送出..."
                    isForeignListening -> "正在收聽外籍技師 (${foreignLang.label})..."
                    else -> "正在收聽我方工程師 (繁體中文)..."
                  },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
              )
              val subText =
                if (isAiTranslating) {
                  translatingPreview
                } else {
                  partialSpeechText.ifBlank { "請對準麥克風說話..." }
                }
              Text(
                text = subText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
              )
            }
            if (activeListeningChannel != ListeningChannel.NONE) {
              TextButton(
                onClick = onCancelVoiceChannel,
                modifier = Modifier.testTag("cancel_voice_channel_btn"),
              ) {
                Text("取消收音", color = MaterialTheme.colorScheme.error)
              }
            }
          }
        }
      }

      Spacer(modifier = Modifier.height(10.dp))

      // Quick Field Scenario Simulation / Direct Input Bar
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          FilterChip(
            selected = selectedInputChannel == "FOREIGN_ENGINEER",
            onClick = { selectedInputChannel = "FOREIGN_ENGINEER" },
            label = { Text("模擬／輸入：外籍技師 (${foreignLang.shortTag})") },
            colors =
              FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                selectedLabelColor = MaterialTheme.colorScheme.primary,
              ),
            modifier = Modifier.testTag("select_foreign_channel_chip"),
          )
          Spacer(modifier = Modifier.width(8.dp))
          FilterChip(
            selected = selectedInputChannel == "LOCAL_ENGINEER",
            onClick = { selectedInputChannel = "LOCAL_ENGINEER" },
            label = { Text("模擬／輸入：我方中文") },
            colors =
              FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                selectedLabelColor = MaterialTheme.colorScheme.secondary,
              ),
            modifier = Modifier.testTag("select_local_channel_chip"),
          )
        }
        TextButton(onClick = { showQuickPhrases = !showQuickPhrases }) {
          Text(if (showQuickPhrases) "收合常用語" else "展開現場語句")
        }
      }

      AnimatedVisibility(visible = showQuickPhrases) {
        val phrases =
          if (selectedInputChannel == "FOREIGN_ENGINEER") {
            foreignLang.sampleForeignPhrases
          } else {
            foreignLang.sampleLocalPhrases
          }
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
          Text(
            text =
              if (selectedInputChannel == "FOREIGN_ENGINEER") {
                "點擊下方外籍技師現場語句，立即體驗即時中文口譯："
              } else {
                "點擊下方我方測試指令，立即體驗反向外語翻譯＋語音播報："
              },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Spacer(modifier = Modifier.height(6.dp))
          Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            phrases.forEachIndexed { idx, phrase ->
              Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f),
                border =
                  BorderStroke(
                    width = 1.dp,
                    color =
                      if (selectedInputChannel == "FOREIGN_ENGINEER") {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                      } else {
                        MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f)
                      },
                  ),
                modifier =
                  Modifier.width(265.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = !isAiTranslating) {
                      onSubmitStatement(selectedInputChannel, phrase)
                    }
                    .testTag("quick_phrase_card_$idx"),
              ) {
                Text(
                  text = phrase,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurface,
                  maxLines = 2,
                  modifier = Modifier.padding(10.dp),
                )
              }
            }
          }
        }
      }

      // Direct Text / Voice Fallback Input Field
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        OutlinedTextField(
          value = manualInputText,
          onValueChange = { manualInputText = it },
          placeholder = {
            Text(
              if (selectedInputChannel == "FOREIGN_ENGINEER") {
                "輸入或貼上外籍技師發言 (${foreignLang.label})..."
              } else {
                "輸入我方中文發言，立即反向翻譯為 ${foreignLang.label}..."
              },
              style = MaterialTheme.typography.bodySmall,
            )
          },
          singleLine = true,
          shape = RoundedCornerShape(14.dp),
          modifier = Modifier.weight(1f).testTag("manual_statement_input"),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Button(
          onClick = {
            if (manualInputText.isNotBlank()) {
              onSubmitStatement(selectedInputChannel, manualInputText)
              manualInputText = ""
            }
          },
          enabled = manualInputText.isNotBlank() && !isAiTranslating,
          shape = RoundedCornerShape(14.dp),
          colors =
            ButtonDefaults.buttonColors(
              containerColor =
                if (selectedInputChannel == "FOREIGN_ENGINEER") {
                  MaterialTheme.colorScheme.primary
                } else {
                  MaterialTheme.colorScheme.secondary
                },
              contentColor =
                if (selectedInputChannel == "FOREIGN_ENGINEER") {
                  MaterialTheme.colorScheme.onPrimary
                } else {
                  MaterialTheme.colorScheme.onSecondary
                },
            ),
          modifier = Modifier.height(52.dp).testTag("submit_statement_btn"),
        ) {
          Icon(
            imageVector = Icons.AutoMirrored.Filled.Send,
            contentDescription = "送出即時口譯",
            modifier = Modifier.size(20.dp),
          )
        }
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UtteranceBilingualCard(
  utterance: Utterance,
  activeTranslatingIds: Set<String> = emptySet(),
  localInterruptedIds: Set<String> = emptySet(),
  onSpeakText: (text: String, langTag: String) -> Unit,
  onTogglePin: (Utterance) -> Unit,
  onOpenSpotlight: (Utterance) -> Unit,
  onRetryTranslation: (Utterance) -> Unit,
) {
  val isForeignSpeaker = utterance.speakerRole == "FOREIGN_ENGINEER"
  val isFailed = utterance.isTranslationFailed()
  val isPending = utterance.isTranslationPending()
  val canRetry = utterance.canRetryTranslation(activeTranslatingIds, localInterruptedIds)

  val accentColor =
    when {
      isFailed -> MaterialTheme.colorScheme.error
      isForeignSpeaker -> MaterialTheme.colorScheme.primary
      else -> MaterialTheme.colorScheme.secondary
    }
  val containerBg =
    when {
      isFailed -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.28f)
      isForeignSpeaker -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.32f)
      else -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.32f)
    }

  val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
  val formattedTime =
    utterance.createdAt?.toDate()?.let { timeFormat.format(it) } ?: "剛剛"

  Card(
    shape = RoundedCornerShape(18.dp),
    colors = CardDefaults.cardColors(containerColor = containerBg),
    border =
      BorderStroke(
        width = if (utterance.isPinnedToMinutes || isFailed) 1.5.dp else 1.dp,
        color =
          if (utterance.isPinnedToMinutes || isFailed) {
            accentColor
          } else {
            accentColor.copy(alpha = 0.35f)
          },
      ),
    modifier = Modifier.fillMaxWidth().testTag("utterance_card_${utterance.id}"),
  ) {
    Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
      // Top Speaker Header + Actions
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
          Box(
            modifier =
              Modifier.size(10.dp).clip(CircleShape).background(accentColor)
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            text = utterance.speakerLabel,
            style = MaterialTheme.typography.labelLarge,
            color = accentColor,
            fontWeight = FontWeight.Bold,
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            text = formattedTime,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
          if (!isFailed && !isPending) {
            IconButton(
              onClick = { onSpeakText(utterance.translatedText, utterance.targetLang) },
              modifier = Modifier.size(36.dp).testTag("speak_translation_btn_${utterance.id}"),
            ) {
              Icon(
                imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = "朗讀翻譯結果",
                tint = accentColor,
                modifier = Modifier.size(20.dp),
              )
            }
            IconButton(
              onClick = { onOpenSpotlight(utterance) },
              modifier = Modifier.size(36.dp).testTag("spotlight_btn_${utterance.id}"),
            ) {
              Icon(
                imageVector = Icons.Default.Fullscreen,
                contentDescription = "放大顯示給對方看",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
              )
            }
          }
          IconButton(
            onClick = { onTogglePin(utterance) },
            modifier = Modifier.size(36.dp).testTag("pin_utterance_btn_${utterance.id}"),
          ) {
            Icon(
              imageVector =
                if (utterance.isPinnedToMinutes) Icons.Filled.PushPin else Icons.Outlined.PushPin,
              contentDescription = "標記為會議紀錄重點",
              tint =
                if (utterance.isPinnedToMinutes) {
                  accentColor
                } else {
                  MaterialTheme.colorScheme.onSurfaceVariant
                },
              modifier = Modifier.size(19.dp),
            )
          }
        }
      }

      Spacer(modifier = Modifier.height(6.dp))

      // Primary Translated Text (or Retry Action when Translation Failed or Interrupted)
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(
          text = utterance.translatedText,
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
          color =
            if (isFailed) {
              MaterialTheme.colorScheme.error
            } else {
              MaterialTheme.colorScheme.onSurface
            },
          modifier = Modifier.weight(1f),
        )
        if (canRetry) {
          Spacer(modifier = Modifier.width(8.dp))
          OutlinedButton(
            onClick = { onRetryTranslation(utterance) },
            modifier = Modifier.testTag("retry_translation_btn_${utterance.id}"),
          ) {
            Icon(
              imageVector = Icons.Default.Refresh,
              contentDescription = "重試翻譯",
              modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text("重試翻譯", style = MaterialTheme.typography.labelLarge)
          }
        }
      }

      Spacer(modifier = Modifier.height(6.dp))
      HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
      Spacer(modifier = Modifier.height(6.dp))

      // Original Text with replay button (Always preserved even if translation failed)
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = "原文 (${utterance.sourceLang})：${utterance.originalText}",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.weight(1f),
        )
        TextButton(
          onClick = { onSpeakText(utterance.originalText, utterance.sourceLang) },
        ) {
          Text("聽原文", style = MaterialTheme.typography.labelSmall)
        }
      }

      // Extracted MRT Railway Technical Terms
      if (utterance.technicalTerms.isNotEmpty()) {
        Spacer(modifier = Modifier.height(6.dp))
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          utterance.technicalTerms.forEach { term ->
            Surface(
              shape = RoundedCornerShape(8.dp),
              color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
              border = BorderStroke(1.dp, accentColor.copy(alpha = 0.35f)),
            ) {
              Text(
                text = term,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
              )
            }
          }
        }
      }
    }
  }
}

@Composable
fun FaceToFaceSpotlightDialog(
  utterance: Utterance,
  onSpeakText: (text: String, langTag: String) -> Unit,
  onDismiss: () -> Unit,
) {
  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Surface(
      modifier = Modifier.fillMaxWidth().padding(16.dp),
      shape = RoundedCornerShape(28.dp),
      color = MaterialTheme.colorScheme.surface,
      border = BorderStroke(2.dp, MaterialTheme.colorScheme.secondary),
    ) {
      Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = RoundedCornerShape(10.dp),
          ) {
            Text(
              text = "現場降噪大字卡 (${utterance.targetLang})",
              style = MaterialTheme.typography.labelLarge,
              color = MaterialTheme.colorScheme.secondary,
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
          }
          IconButton(onClick = onDismiss) {
            Icon(
              imageVector = Icons.Default.Close,
              contentDescription = "關閉大字卡",
            )
          }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
          text = utterance.translatedText,
          fontSize = 28.sp,
          lineHeight = 36.sp,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.onSurface,
          textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(12.dp))

        Text(
          text = utterance.originalText,
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(24.dp))

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Button(
            onClick = { onSpeakText(utterance.translatedText, utterance.targetLang) },
            colors =
              ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondary,
                contentColor = MaterialTheme.colorScheme.onSecondary,
              ),
            modifier = Modifier.weight(1f).height(52.dp),
          ) {
            Icon(imageVector = Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("大聲朗讀譯文 (${utterance.targetLang})", fontWeight = FontWeight.Bold)
          }
          OutlinedButton(
            onClick = onDismiss,
            modifier = Modifier.height(52.dp),
          ) {
            Text("關閉")
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CreateTestSessionDialog(
  onDismiss: () -> Unit,
  onCreateSession: (
    title: String,
    metroLine: String,
    testCategory: String,
    languageOption: ForeignLanguageOption,
  ) -> Unit,
) {
  var title by remember { mutableStateOf("板南線號誌與車輛聯調測試") }
  var selectedLine by remember { mutableStateOf(METRO_LINES.first()) }
  var selectedCategory by remember { mutableStateOf(TEST_CATEGORIES.first()) }
  var selectedLang by remember { mutableStateOf(SUPPORTED_FOREIGN_LANGUAGES.first()) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text(
        text = "新增捷運測試雙向口譯場次",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
      )
    },
    text = {
      Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        OutlinedTextField(
          value = title,
          onValueChange = { title = it },
          label = { Text("測試會議／工項名稱") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("new_session_title_input"),
        )

        Text(
          text = "外籍技師語言：",
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.primary,
        )
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          SUPPORTED_FOREIGN_LANGUAGES.forEach { lang ->
            FilterChip(
              selected = selectedLang.code == lang.code,
              onClick = { selectedLang = lang },
              label = { Text(lang.label) },
            )
          }
        }

        Text(
          text = "捷運路線／機廠：",
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.primary,
        )
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          METRO_LINES.forEach { line ->
            FilterChip(
              selected = selectedLine == line,
              onClick = { selectedLine = line },
              label = { Text(line) },
            )
          }
        }

        Text(
          text = "測試系統類別：",
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.primary,
        )
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          TEST_CATEGORIES.forEach { cat ->
            FilterChip(
              selected = selectedCategory == cat,
              onClick = { selectedCategory = cat },
              label = { Text(cat) },
            )
          }
        }
      }
    },
    confirmButton = {
      Button(
        onClick = {
          onCreateSession(
            title.ifBlank { "捷運現場測試會議" },
            selectedLine,
            selectedCategory,
            selectedLang,
          )
          onDismiss()
        },
        modifier = Modifier.testTag("confirm_create_session_btn"),
      ) {
        Text("建立並啟動口譯")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("取消")
      }
    },
  )
}
