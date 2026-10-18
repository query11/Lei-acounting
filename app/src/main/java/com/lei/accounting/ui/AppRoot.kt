@file:OptIn(ExperimentalMaterial3Api::class)

package com.lei.accounting.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lei.accounting.core.model.BillType
import com.lei.accounting.core.model.CaptureSource
import com.lei.accounting.core.model.PayApp
import com.lei.accounting.core.rule.Categories
import com.lei.accounting.data.AiStatus
import com.lei.accounting.data.BillEntity
import java.time.LocalTime

private val tabTitles = listOf("Lei记账", "收支统计", "设置")

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

        AppScaffold(
            tab = tab,
            onTab = { tab = it },
            onAdd = { vm.edit(newManualBill()) },
            snackbar = snackbar,
        ) { current ->
            when (current) {
                0 -> BillsScreen(vm)
                1 -> StatsScreen(vm)
                else -> SettingsScreen(vm)
            }
        }

        editing?.let { bill ->
            BillEditSheet(
                bill = bill,
                onDismiss = { vm.edit(null) },
                onSave = vm::save,
                onDelete = vm::delete,
                onReclassify = vm::reclassify,
            )
        }
    }
}

private fun greeting(hour: Int = LocalTime.now().hour): String = when (hour) {
    in 5..10 -> "早上好"
    in 11..12 -> "中午好"
    in 13..17 -> "下午好"
    in 18..22 -> "晚上好"
    else -> "夜深了"
}

/** 顶栏 + 底部导航 + 「记一笔」按钮；页面之间用淡入淡出切换（Material 的 fade through） */
@Composable
fun AppScaffold(
    tab: Int,
    onTab: (Int) -> Unit,
    onAdd: () -> Unit,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    content: @Composable (Int) -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        if (tab == 0) {
                            Text(
                                greeting(),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(tabTitles[tab], style = MaterialTheme.typography.headlineSmall)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest, tonalElevation = 0.dp) {
                val itemColors = NavigationBarItemDefaults.colors(
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                )
                val haptic = LocalHapticFeedback.current
                listOf(Icons.Default.Home to "账单", Icons.Default.DateRange to "统计", Icons.Default.Settings to "设置")
                    .forEachIndexed { i, (icon, label) ->
                        NavigationBarItem(
                            selected = tab == i,
                            onClick = {
                                if (tab != i) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onTab(i)
                            },
                            icon = { Icon(icon, null) },
                            label = { Text(label) },
                            colors = itemColors,
                        )
                    }
            }
        },
        floatingActionButton = {
            AnimatedVisibility(
                visible = tab == 0,
                enter = scaleIn(spring(dampingRatio = 0.7f)) + fadeIn(),
                exit = scaleOut(tween(120)) + fadeOut(tween(120)),
            ) {
                ExtendedFloatingActionButton(
                    onClick = onAdd,
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("记一笔", style = MaterialTheme.typography.labelLarge) },
                    shape = RoundedCornerShape(20.dp),
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        AnimatedContent(
            targetState = tab,
            modifier = Modifier.fillMaxSize().padding(padding),
            transitionSpec = {
                (fadeIn(tween(220, delayMillis = 70)) + scaleIn(tween(220, delayMillis = 70), initialScale = 0.98f)) togetherWith
                    fadeOut(tween(90))
            },
            label = "tabs",
        ) { current ->
            Box(Modifier.fillMaxSize()) { content(current) }
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
