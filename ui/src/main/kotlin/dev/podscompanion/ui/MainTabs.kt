package dev.podscompanion.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch

private data class Tab(val title: Int, val icon: ImageVector)

private val TABS = listOf(
    Tab(R.string.tab_headphones, Icons.Filled.Headphones),
    Tab(R.string.tab_music, Icons.Filled.MusicNote),
    Tab(R.string.tab_stats, Icons.Filled.BarChart),
    Tab(R.string.tab_find, Icons.Filled.TravelExplore),
    Tab(R.string.tab_settings, Icons.Filled.Settings),
)

/**
 * Вкладки внизу экрана, между которыми можно листать пальцем вправо-влево, как в играх:
 * «Наушники», «Музыка», «Статистика», «Найти», «Настройки». Нажатие на вкладку прокручивает к ней,
 * «назад» на любой вкладке, кроме первой, возвращает на первую.
 */
@Composable
fun MainTabs(
    headphones: @Composable () -> Unit,
    music: @Composable () -> Unit,
    stats: @Composable () -> Unit,
    find: @Composable () -> Unit,
    settings: @Composable () -> Unit,
) {
    // rememberPagerState сохраняет вкладку: вернулись из настроек наушников — открыта та же.
    val pager = rememberPagerState(pageCount = { TABS.size })
    val scope = rememberCoroutineScope()
    BackHandler(enabled = pager.currentPage != 0) { scope.launch { pager.animateScrollToPage(0) } }

    Scaffold(
        bottomBar = {
            NavigationBar {
                TABS.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        // targetPage: подсветка переезжает на вкладку уже во время свайпа.
                        selected = pager.targetPage == index,
                        onClick = { scope.launch { pager.animateScrollToPage(index) } },
                        icon = { Icon(tab.icon, null) },
                        label = { Text(stringResource(tab.title)) },
                    )
                }
            }
        },
    ) { padding ->
        HorizontalPager(
            state = pager,
            // Внутренние экраны со своим Scaffold не добавляют отступ под системную панель второй раз.
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding),
        ) { page ->
            when (page) {
                0 -> headphones()
                1 -> music()
                2 -> stats()
                3 -> find()
                else -> settings()
            }
        }
    }
}
