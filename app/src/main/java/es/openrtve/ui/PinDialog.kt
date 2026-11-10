package es.openrtve.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import es.openrtve.R

private const val DELETE_PIN = "0000"

/**
 * Confirmación para borrar un título de la base de datos. El PIN (0000) se enseña a
 * propósito: no protege de nadie, solo evita borrar sin querer. Con [unlocked] (ya se
 * metió en esta sesión) solo pide confirmar, y el foco empieza en Cancelar.
 */
@Composable
fun PinDialog(
    name: String,
    unlocked: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    notice: String? = null,
) {
    var entered by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    val initial = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { initial.requestFocus() } }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .width(340.dp)
                .background(Color(0xFF1E2130), RoundedCornerShape(16.dp))
                .padding(20.dp),
        ) {
            notice?.let { Text(it, color = Color(0xFFFFB4A9), fontSize = 14.sp) }
            Text(stringResource(R.string.pin_title, name), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            if (unlocked) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PinButton(stringResource(R.string.pin_cancel), Modifier.weight(1f).focusRequester(initial), onDismiss)
                    PinButton(stringResource(R.string.pin_delete), Modifier.weight(1f), onConfirm)
                }
            } else {
                Text(stringResource(R.string.pin_hint), color = Color(0xFFB8BCD0), fontSize = 14.sp)
                Text(
                    text = entered.padEnd(4, '·').toCharArray().joinToString("  "),
                    color = Color.White,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (wrong) Text(stringResource(R.string.pin_wrong), color = Color(0xFFFF8A80), fontSize = 13.sp)
                fun press(digit: Char) {
                    wrong = false
                    val next = entered + digit
                    if (next.length < DELETE_PIN.length) {
                        entered = next
                    } else if (next == DELETE_PIN) {
                        onConfirm()
                    } else {
                        entered = ""
                        wrong = true
                    }
                }
                listOf("123", "456", "789").forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { d -> PinButton(d.toString(), Modifier.weight(1f)) { press(d) } }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PinButton("⌫", Modifier.weight(1f)) { entered = entered.dropLast(1); wrong = false }
                    PinButton("0", Modifier.weight(1f).focusRequester(initial)) { press('0') }
                    PinButton(stringResource(R.string.pin_cancel), Modifier.weight(1f), onDismiss)
                }
            }
        }
    }
}

@Composable
private fun PinButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(44.dp)
            .onFocusChanged { focused = it.isFocused }
            .background(if (focused) Color(0xFFFF1744) else Color(0xFF2E3247), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
    ) {
        Text(label, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }
}
