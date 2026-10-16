@file:OptIn(ExperimentalMaterial3Api::class)

package com.lei.accounting.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lei.accounting.core.model.BillType
import com.lei.accounting.core.model.CaptureSource
import com.lei.accounting.core.model.PayApp
import com.lei.accounting.core.rule.Categories
import com.lei.accounting.data.AiStatus
import com.lei.accounting.data.BillEntity

private val Green = Color(0xFF2E7D32)

@Composable
fun LeiTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = Color(0xFF81C784))
        else -> lightColorScheme(primary = Green)
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun AppRoot(vm: MainViewModel) {
    LeiTheme {
        var tab by rememberSaveable { mutableIntStateOf(0) }
        val snackbar = remember { SnackbarHostState() }
        val message by vm.message.collectAsStateWithLifecycle()
        val editing by vm.editing.collectAsStateWithLifecycle()

        LaunchedEffect(message) {
            message?.let {
                snackbar.showSnackbar(it)
                vm.consumeMessage()
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(title = { Text(listOf("Lei记账", "收支统计", "设置")[tab]) })
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Default.Home, null) }, label = { Text("账单") })
                    NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.Default.DateRange, null) }, label = { Text("统计") })
                    NavigationBarItem(tab == 2, { tab = 2 }, { Icon(Icons.Default.Settings, null) }, label = { Text("设置") })
                }
            },
            floatingActionButton = {
                if (tab == 0) {
                    FloatingActionButton(onClick = { vm.edit(newManualBill()) }) {
                        Icon(Icons.Default.Add, contentDescription = "记一笔")
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (tab) {
                    0 -> BillsScreen(vm)
                    1 -> StatsScreen(vm)
                    else -> SettingsScreen(vm)
                }
            }
        }

        editing?.let { bill ->
            BillEditDialog(
                bill = bill,
                onDismiss = { vm.edit(null) },
                onSave = vm::save,
                onDelete = vm::delete,
                onReclassify = vm::reclassify,
            )
        }
    }
}

private fun newManualBill() = BillEntity(
    amountCents = 0,
    type = BillType.EXPENSE.name,
    category = Categories.expense.first(),
    app = PayApp.MANUAL.name,
    sources = CaptureSource.MANUAL.name,
    time = System.currentTimeMillis(),
    aiStatus = AiStatus.USER_EDITED,
)
