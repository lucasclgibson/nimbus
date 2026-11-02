package io.github.lucasclgibson.nimbus.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import io.github.lucasclgibson.nimbus.ui.theme.C
import io.github.lucasclgibson.nimbus.ui.theme.T
import kotlinx.coroutines.delay

/**
 * 4-digit PIN entry with an on-screen keypad (Fire TV remotes have no number keys; phone remotes and
 * keyboards can type digits too). [onSubmit] returns false for a wrong PIN.
 */
@Composable
fun PinDialog(title: String, subtitle: String = "", onSubmit: (String) -> Boolean, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var pin by remember { mutableStateOf("") }
        var wrong by remember { mutableStateOf(false) }
        val first = remember { FocusRequester() }

        fun press(d: String) {
            if (pin.length >= 4) return
            wrong = false
            pin += d
        }

        LaunchedEffect(pin) {
            if (pin.length == 4) {
                delay(120)
                if (!onSubmit(pin)) {
                    wrong = true
                    pin = ""
                }
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xD902040A))
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val c = e.utf16CodePoint.toChar()
                    if (c in '0'..'9') {
                        press(c.toString())
                        true
                    } else false
                },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.background(C.Panel, RoundedCornerShape(18.dp)).padding(horizontal = 36.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(title, style = T.H2, color = C.Text, textAlign = TextAlign.Center)
                if (subtitle.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(subtitle, style = T.Body, color = C.TextDim, textAlign = TextAlign.Center)
                }
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    repeat(4) { i ->
                        Box(
                            Modifier.size(16.dp).background(
                                if (i < pin.length) C.Text else Color(0x33FFFFFF), CircleShape,
                            )
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(if (wrong) "Wrong PIN – try again" else " ", style = T.Label, color = C.Bad)
                Spacer(Modifier.height(10.dp))
                val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("⌫", "0", ""))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    rows.forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { k ->
                                if (k.isEmpty()) {
                                    Spacer(Modifier.size(64.dp, 48.dp))
                                } else {
                                    FocusSurface(
                                        onClick = { if (k == "⌫") pin = pin.dropLast(1) else press(k) },
                                        modifier = Modifier.size(64.dp, 48.dp).then(if (k == "1") Modifier.focusRequester(first) else Modifier),
                                        shape = RoundedCornerShape(10.dp),
                                        color = C.Glass,
                                        focusedColor = C.Focus,
                                        contentColor = C.Text,
                                        focusedContentColor = C.OnFocus,
                                        focusedScale = 1.08f,
                                        border = false,
                                    ) {
                                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            if (k == "⌫") Icon(Icons.AutoMirrored.Rounded.Backspace, "Delete", Modifier.size(20.dp))
                                            else Text(k, style = T.H2)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp).width(1.dp))
            }
        }
        first.RequestOnce()
    }
}
