@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.borealroutes.app

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class Screen { SEARCH, RESULTS, JOURNEY, FAVOURITES, JOURNEYS, MORE }

@Composable
fun BorealApp(
    vm: BorealViewModel,
    themeMode: AppThemeMode,
    onThemeModeChange: (AppThemeMode) -> Unit
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var screen by remember { mutableStateOf(Screen.SEARCH) }
    var closeDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    BackHandler(true) {
        screen = when (screen) {
            Screen.JOURNEY -> { vm.selectJourney(null); Screen.RESULTS }
            Screen.RESULTS, Screen.FAVOURITES, Screen.JOURNEYS, Screen.MORE -> Screen.SEARCH
            Screen.SEARCH -> { closeDialog = true; Screen.SEARCH }
        }
    }

    if (closeDialog) {
        AlertDialog(
            onDismissRequest = { closeDialog = false },
            title = { Text("Close Boreal?") },
            text = { Text("Are you sure you want to close the app?") },
            dismissButton = { TextButton(onClick = { closeDialog = false }) { Text("Stay") } },
            confirmButton = {
                TextButton(onClick = {
                    closeDialog = false
                    (context as? android.app.Activity)?.finishAndRemoveTask()
                }) { Text("Close app") }
            }
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (screen != Screen.JOURNEY) {
                BorealBottomBar(screen) { target ->
                    screen = target
                    if (target == Screen.RESULTS && state.journeys.isEmpty()) screen = Screen.SEARCH
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (screen) {
                Screen.SEARCH -> SearchScreen(state, vm) {
                    vm.search()
                    screen = Screen.RESULTS
                }
                Screen.RESULTS -> ResultsScreen(state, vm,
                    onBack = { screen = Screen.SEARCH },
                    onJourney = { vm.selectJourney(it); screen = Screen.JOURNEY })
                Screen.JOURNEY -> state.selectedJourney?.let { journey ->
                    JourneyScreen(
                        journey = journey,
                        isFavorite = vm.isFavorite(journey),
                        onFavorite = { vm.favorite(journey) },
                        onBack = { vm.selectJourney(null); screen = Screen.RESULTS }
                    )
                } ?: run { screen = Screen.RESULTS }
                Screen.FAVOURITES -> SimpleJourneyCollection(
                    title = "Favourites",
                    journeys = (state.favoriteJourneys + state.journeys.filter(vm::isFavorite)).distinctBy(JourneyLogic::itineraryKey),
                    emptyText = "Favourite a journey with the star on its detail screen.",
                    onJourney = { vm.selectJourney(it); screen = Screen.JOURNEY }
                )
                Screen.JOURNEYS -> SimpleJourneyCollection(
                    title = "Journeys",
                    journeys = (state.journeys + state.recentJourneys).distinctBy(JourneyLogic::itineraryKey),
                    emptyText = "Your latest search journeys will appear here.",
                    onJourney = { vm.selectJourney(it); screen = Screen.JOURNEY }
                )
                Screen.MORE -> MoreScreen(themeMode, onThemeModeChange)
            }
        }
    }

    state.error?.let { error ->
        AlertDialog(
            onDismissRequest = vm::clearError,
            title = { Text("Boreal") },
            text = { Text(error) },
            confirmButton = { TextButton(onClick = vm::clearError) { Text("OK") } }
        )
    }
}

@Composable
private fun BorealBottomBar(screen: Screen, onSelect: (Screen) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
        val items = listOf(
            Triple(Screen.SEARCH, "Search", Icons.Outlined.Search),
            Triple(Screen.FAVOURITES, "Favourites", Icons.Outlined.Star),
            Triple(Screen.JOURNEYS, "Journeys", Icons.Outlined.DirectionsBus),
            Triple(Screen.MORE, "More", Icons.Outlined.Menu)
        )
        items.forEach { (target, label, icon) ->
            NavigationBarItem(
                selected = screen == target || (target == Screen.SEARCH && screen == Screen.RESULTS),
                onClick = { onSelect(target) },
                icon = { Icon(icon, label) },
                label = { Text(label, fontSize = 11.sp) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = Color.Transparent,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }
    }
}

@Composable
private fun SearchScreen(state: BorealUiState, vm: BorealViewModel, onSearch: () -> Unit) {
    val context = LocalContext.current
    val date = remember(state.dateIso) { runCatching { LocalDate.parse(state.dateIso) }.getOrElse { LocalDate.now() } }
    val time = remember(state.time) { runCatching { LocalTime.parse(state.time) }.getOrElse { LocalTime.now() } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(18.dp, 24.dp, 18.dp, 40.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text("Boreal", fontSize = 28.sp, fontWeight = FontWeight.Black)
            Text("Better journeys. Every day.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
        item {
            Text("Plan journey", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        item {
            LocationField(
                label = "From",
                value = state.fromText,
                suggestions = state.fromSuggestions,
                onValue = vm::updateFrom,
                onChoose = vm::chooseFrom
            )
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                FilledTonalIconButton(onClick = vm::swap) {
                    Icon(Icons.Default.SwapVert, "Swap start and destination")
                }
            }
        }
        item {
            LocationField(
                label = "To",
                value = state.toText,
                suggestions = state.toSuggestions,
                onValue = vm::updateTo,
                onChoose = vm::chooseTo
            )
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = state.timeMode == TimeMode.LEAVE_AT,
                            onClick = { vm.setTimeMode(TimeMode.LEAVE_AT) },
                            label = { Text("Leave at") }
                        )
                        FilterChip(
                            selected = state.timeMode == TimeMode.ARRIVE_BY,
                            onClick = { vm.setTimeMode(TimeMode.ARRIVE_BY) },
                            label = { Text("Arrive by") }
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                DatePickerDialog(
                                    context,
                                    { _, y, m, d -> vm.setDate(LocalDate.of(y, m + 1, d).toString()) },
                                    date.year, date.monthValue - 1, date.dayOfMonth
                                ).show()
                            }
                        ) { Icon(Icons.Outlined.CalendarMonth, null); Spacer(Modifier.width(6.dp)); Text(date.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK))) }
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                TimePickerDialog(
                                    context,
                                    { _, h, m -> vm.setTime(String.format(Locale.UK, "%02d:%02d", h, m)) },
                                    time.hour, time.minute, true
                                ).show()
                            }
                        ) { Icon(Icons.Outlined.Schedule, null); Spacer(Modifier.width(6.dp)); Text(state.time) }
                    }
                    Text("Only the selected calendar day is searched.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
            }
        }
        item {
            ExpandableOptions(state, vm)
        }
        item {
            Button(
                onClick = onSearch,
                enabled = !state.searching && state.fromText.isNotBlank() && state.toText.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                if (state.searching) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(Icons.Default.Search, null)
                Spacer(Modifier.width(8.dp))
                Text(if (state.searching) "Searching…" else "Search nationwide buses", fontWeight = FontWeight.Bold)
            }
        }
        item {
            Text("Bus only by default · coaches excluded unless enabled · trains/ferries/planes opt-in", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        }
    }
}

@Composable
private fun LocationField(
    label: String,
    value: String,
    suggestions: List<Place>,
    onValue: (String) -> Unit,
    onChoose: (Place) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValue,
            label = { Text(label) },
            leadingIcon = { Icon(Icons.Outlined.Place, null) },
            trailingIcon = if (value.isNotBlank()) {
                { IconButton(onClick = { onValue("") }) { Icon(Icons.Default.Close, "Clear") } }
            } else null,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp)
        )
        if (suggestions.isNotEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column {
                    suggestions.take(7).forEachIndexed { i, p ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onChoose(p) }.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Outlined.LocationOn, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(p.name, fontWeight = FontWeight.SemiBold)
                                if (p.subtitle.isNotBlank()) Text(p.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                            }
                        }
                        if (i != suggestions.take(7).lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        }
    }
}

@Composable
private fun ExpandableOptions(state: BorealUiState, vm: BorealViewModel) {
    var open by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable { open = !open }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Tune, null)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Journey options", fontWeight = FontWeight.Bold)
                    Text("Via, transport types, avoid services, day trip", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                }
                Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
            }
            if (open) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LocationField("Via (optional)", state.viaText, state.viaSuggestions, vm::updateVia, vm::chooseVia)
                    Text("Additional transport", fontWeight = FontWeight.SemiBold)
                    OptionSwitch("Coaches", state.includeCoaches, vm::setCoaches)
                    OptionSwitch("Trains", state.includeTrains, vm::setTrains)
                    OptionSwitch("Ferries", state.includeFerries, vm::setFerries)
                    OptionSwitch("Planes", state.includePlanes, vm::setPlanes)
                    OutlinedTextField(
                        value = state.avoid,
                        onValueChange = vm::setAvoid,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Avoid services/operators") },
                        placeholder = { Text("e.g. 724, Stagecoach") },
                        singleLine = true
                    )
                    OptionSwitch("Day trip / return home", state.dayTrip, vm::setDayTrip)
                    if (state.dayTrip) {
                        OutlinedTextField(
                            value = state.breakMinutes.toString(),
                            onValueChange = { vm.setBreakMinutes(it.toIntOrNull() ?: 0) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Break at destination (minutes)") },
                            singleLine = true
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OptionSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ResultsScreen(
    state: BorealUiState,
    vm: BorealViewModel,
    onBack: () -> Unit,
    onJourney: (Itinerary) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Journeys") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
        )
        if (state.searching) LinearProgressIndicator(Modifier.fillMaxWidth())
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Text(state.status, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.rawScanned > 0) Text("${state.rawScanned} timetable options scanned", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
            Spacer(Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val prefs = listOf(
                    SortPreference.FASTEST to "Fastest",
                    SortPreference.FEWEST_CHANGES to "Changes",
                    SortPreference.LEAST_WALKING to "Walking"
                )
                prefs.forEachIndexed { i, pair ->
                    SegmentedButton(
                        selected = state.sortPreference == pair.first,
                        onClick = { vm.setSort(pair.first) },
                        shape = SegmentedButtonDefaults.itemShape(i, prefs.size),
                        label = { Text(pair.second, fontSize = 11.sp) }
                    )
                }
            }
        }
        if (state.journeys.isEmpty() && state.searching) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(14.dp))
                    Text("Sweeping the selected day's timetable…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else if (state.journeys.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No journeys found", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(14.dp, 6.dp, 14.dp, 30.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(state.journeys, key = { JourneyLogic.itineraryKey(it) }) { it ->
                    JourneyCard(it, onClick = { onJourney(it) })
                }
                if (state.searching) item {
                    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp)); Text("Still searching for more…", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun JourneyCard(itinerary: Itinerary, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(TimeFormat.time(itinerary.startTime), fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
                    Text("Depart", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                }
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    Text(TimeFormat.duration(itinerary.durationMinutes), fontWeight = FontWeight.Bold)
                    Text("${itinerary.changes} change${if (itinerary.changes == 1) "" else "s"}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                }
                Spacer(Modifier.width(14.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(TimeFormat.time(itinerary.endTime), fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
                    Text("Arrive", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                }
                Spacer(Modifier.width(4.dp)); Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                itinerary.mergedLegs().take(8).forEach { leg ->
                    val mode = leg.effectiveMode()
                    if (mode in setOf("FOOT", "WALK")) {
                        AssistChip(onClick = {}, label = { Text("Walk", fontSize = 10.sp) })
                    } else {
                        RouteBadge(leg)
                    }
                }
            }
            Spacer(Modifier.height(7.dp))
            Text("${TimeFormat.duration(itinerary.durationMinutes)} · ${itinerary.walkingMinutes} min walking", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        }
    }
}

@Composable
private fun SimpleJourneyCollection(title: String, journeys: List<Itinerary>, emptyText: String, onJourney: (Itinerary) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(title) }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface))
        if (journeys.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) { Text(emptyText, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            LazyColumn(contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(journeys) { JourneyCard(it) { onJourney(it) } }
            }
        }
    }
}

@Composable
private fun MoreScreen(
    themeMode: AppThemeMode,
    onThemeModeChange: (AppThemeMode) -> Unit
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("More", fontSize = 26.sp, fontWeight = FontWeight.Black) }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Palette, null)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("Appearance", fontWeight = FontWeight.Bold)
                            Text("Choose how Boreal follows your phone theme", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        }
                    }
                    AppThemeMode.entries.forEach { mode ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onThemeModeChange(mode) }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = themeMode == mode, onClick = { onThemeModeChange(mode) })
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(mode.label, fontWeight = if (themeMode == mode) FontWeight.SemiBold else FontWeight.Normal)
                                if (mode == AppThemeMode.SYSTEM) {
                                    Text("Automatically follows Android", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("Boreal 5.1 Native", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("Kotlin + Jetpack Compose", color = MaterialTheme.colorScheme.primary)
                    Text("The app no longer uses WebView. Journey search, navigation and maps now run through native Android components.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Map", fontWeight = FontWeight.Bold)
                    Text("MapLibre Native · OpenFreeMap / OpenStreetMap", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    Spacer(Modifier.height(12.dp))
                    Text("Bus search", fontWeight = FontWeight.Bold)
                    Text("Transitous MOTIS v6 timetable sweep · selected day only", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
            }
        }
    }
}

object TimeFormat {
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    fun time(t: java.time.OffsetDateTime): String = t.format(timeFormatter)
    fun duration(minutes: Long): String {
        val h = minutes / 60
        val m = minutes % 60
        return if (h > 0) "${h} hr ${m} min" else "$m min"
    }
}
