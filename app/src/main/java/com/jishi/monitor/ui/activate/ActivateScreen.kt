package com.jishi.monitor.ui.activate

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jishi.monitor.ui.theme.JishiColors

/**
 * 登录页：服务端地址 + 账号 + 密码。
 *
 * 该版本不内置任何服务端地址与账号（源码可公开），由使用者填写后登录并本地加密保存。
 */
@Composable
fun ActivateScreen(viewModel: ActivateViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var host by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pwd by remember { mutableStateOf("") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(JishiColors.backgroundGradient)
            // 全面屏/刘海适配
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // 品牌锚点：与桌面图标同源的镜头标记
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(com.jishi.monitor.R.drawable.ic_brand_mark),
                contentDescription = null,
                modifier = Modifier.size(54.dp),
            )
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("即视", style = MaterialTheme.typography.headlineMedium, fontSize = 28.sp)
                Box(
                    Modifier
                        .padding(horizontal = 7.dp)
                        .size(11.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(JishiColors.Live),
                )
                Text(
                    "Live",
                    style = MaterialTheme.typography.headlineMedium,
                    fontSize = 28.sp,
                    color = JishiColors.AccentSolid,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text("连接你的即视服务端", style = MaterialTheme.typography.bodyMedium, color = JishiColors.TextSecondary)
            Spacer(Modifier.height(24.dp))

            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                singleLine = true,
                label = { Text("服务端地址") },
                placeholder = { Text("例如 your-server.com:10000") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            )

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = user,
                onValueChange = { user = it },
                singleLine = true,
                label = { Text("账号") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Next),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            )

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = pwd,
                onValueChange = { pwd = it },
                singleLine = true,
                label = { Text("密码") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            )

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = { viewModel.submit(host, user, pwd) },
                enabled = !state.checking && host.isNotBlank() && user.isNotBlank() && pwd.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = JishiColors.AccentSolid,
                    contentColor = Color.White,
                    // 禁用态用中性灰，避免默认低对比紫灰（UI 详检 P1）
                    disabledContainerColor = Color(0xFFE5EAF2),
                    disabledContentColor = JishiColors.TextMuted,
                ),
            ) {
                if (state.checking) {
                    CircularProgressIndicator(modifier = Modifier.height(22.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Text("进入", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(Modifier.height(14.dp))
            state.error?.let {
                Text(it, color = JishiColors.Live, style = MaterialTheme.typography.bodySmall)
            }
            if (state.lockedUntilMs > System.currentTimeMillis()) {
                val mins = ((state.lockedUntilMs - System.currentTimeMillis()) / 60000 + 1)
                Spacer(Modifier.height(6.dp))
                Text("已锁定，请于约 " + mins + " 分钟后重试", style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.height(20.dp))
            Text(
                "登录信息仅保存在本机（系统密钥库加密）；卸载重装后需重新填写。",
                style = MaterialTheme.typography.labelSmall,
                color = JishiColors.TextWeak,
            )
        }
    }
}
