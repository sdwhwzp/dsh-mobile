package com.clarklevis.dsh.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.clarklevis.dsh.android.DshAndroidApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Password input belongs to this dialog only and is never saved in instance state. */
@Composable
internal fun MobileAccountLogin(onDismiss: () -> Unit) {
    val hosts = (LocalContext.current.applicationContext as DshAndroidApplication).hosts
    val scope = rememberCoroutineScope()
    var origin by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("账号登录") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("登录现有 Harness 账号。登录后可在主机列表切换账号。")
                OutlinedTextField(origin, { origin = it }, label = { Text("HTTPS 服务器地址") }, singleLine = true, enabled = !busy)
                OutlinedTextField(username, { username = it }, label = { Text("用户名") }, singleLine = true, enabled = !busy)
                OutlinedTextField(password, { password = it }, label = { Text("密码") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), enabled = !busy)
                if (busy) CircularProgressIndicator()
                error?.let { Text(it) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && origin.isNotBlank() && username.isNotBlank() && password.isNotEmpty(), onClick = {
                busy = true
                error = null
                scope.launch {
                    try {
                        hosts.loginAccount(origin, username, password)
                        password = ""
                        onDismiss()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        error = failure.message ?: "登录失败"
                    } finally { busy = false }
                }
            }) { Text("登录") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } },
    )
}
