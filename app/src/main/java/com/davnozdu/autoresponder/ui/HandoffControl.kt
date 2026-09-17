package com.davnozdu.autoresponder.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.davnozdu.autoresponder.respond.Handoff
import kotlinx.coroutines.delay

@Composable
fun HandoffControl(who: String, channel: String, initiallyOpen: Boolean = false) {
    val context=LocalContext.current
    var chooser by remember(who,channel) { mutableStateOf(initiallyOpen) }
    var revision by remember { mutableStateOf(0) }
    LaunchedEffect(who,channel) { while(true) { delay(15_000); revision++ } }
    val label=remember(who,channel,revision) { Handoff.label(context,who,channel) }
    Column(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=4.dp)) {
        if(label!=null) Text(label,style=MaterialTheme.typography.bodySmall)
        TextButton(onClick={ if(label==null) chooser=true else { Handoff.resume(context,who,channel); revision++ } }) {
            Text(if(label==null) "Отвечаю я" else "Вернуть автоответ")
        }
    }
    if(chooser) {
        var minutes by remember { mutableStateOf(60) }
        AlertDialog(onDismissRequest={chooser=false},title={Text("Отвечаю я")},text={
            Column {
                Text("Обычные AI-ответы и шаблоны этому клиенту будут приостановлены. CRM и обработка звонков работают как раньше.")
                listOf(30 to "30 минут",60 to "1 час",0 to "До моего включения").forEach { (value,text) ->
                    TextButton(onClick={minutes=value}) { Text((if(minutes==value) "● " else "○ ")+text) }
                }
                Text("После возврата робот отвечает только на новые обращения.",style=MaterialTheme.typography.bodySmall)
            }
        },confirmButton={TextButton(onClick={Handoff.pause(context,who,channel,minutes);chooser=false;revision++}) {Text("Я отвечаю")}},
            dismissButton={TextButton(onClick={chooser=false}) {Text("Отмена")}})
    }
}
