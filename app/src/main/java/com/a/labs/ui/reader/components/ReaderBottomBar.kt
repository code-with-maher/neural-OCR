package com.a.labs.ui.reader.components

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.a.labs.data.audio.AudioState

@Composable
fun ReaderBottomBar(
    audioState: AudioState,
    currentPageNumber: Int,
    totalPages: Int?,
    onPrevPage: () -> Unit,
    onNextPage: () -> Unit,
    onSeekBackward: () -> Unit,
    onSeekForward: () -> Unit,
    onPlayButtonClick: () -> Unit
) {
    Surface(tonalElevation = 8.dp, shadowElevation = 8.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onPrevPage) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = "الصفحة السابقة")
                }
                IconButton(onClick = onSeekBackward) {
                    Icon(Icons.Default.Replay10, contentDescription = "تراجع 10 ثواني")
                }

                val btnText = when (audioState) {
                    AudioState.PROCESSING -> "جاري المعالجة"
                    AudioState.PLAYING -> "إيقاف مؤقت"
                    else -> "تشغيل"
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.semantics(mergeDescendants = true) {
                        contentDescription = btnText
                        role = Role.Button
                        onClick(label = btnText) {
                            onPlayButtonClick()
                            true
                        }
                    }
                ) {
                    FilledIconButton(
                        onClick = onPlayButtonClick,
                        modifier = Modifier
                            .size(56.dp)
                            .clearAndSetSemantics {},
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = if (audioState == AudioState.PROCESSING) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.primaryContainer
                            }
                        )
                    ) {
                        Crossfade(targetState = audioState, label = "AudioButtonAnimation") { state ->
                            when (state) {
                                AudioState.PROCESSING -> {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.5.dp,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                                AudioState.PLAYING -> {
                                    Icon(Icons.Default.Pause, contentDescription = null)
                                }
                                else -> {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(4.dp))

                    Text(
                        text = btnText,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clearAndSetSemantics {}
                    )
                }

                IconButton(onClick = onSeekForward) {
                    Icon(Icons.Default.Forward10, contentDescription = "تقديم 10 ثواني")
                }
                IconButton(onClick = onNextPage) {
                    Icon(Icons.Default.SkipNext, contentDescription = "الصفحة التالية")
                }
            }

            Spacer(Modifier.height(8.dp))

            Text(
                text = "صفحة $currentPageNumber من ${totalPages ?: "?"}",
                modifier = Modifier
                    .fillMaxWidth()
                    .clearAndSetSemantics {
                        heading()
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = "صفحة $currentPageNumber من أصل ${totalPages ?: "غير محدد"}"
                    }
            )
        }
    }
}