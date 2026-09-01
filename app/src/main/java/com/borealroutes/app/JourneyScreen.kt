@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.borealroutes.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.TransferWithinAStation
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun JourneyScreen(
    journey: Itinerary,
    isFavorite: Boolean,
    onFavorite: () -> Unit,
    onBack: () -> Unit
) {
    val legs = remember(journey) { journey.mergedLegs() }
    var focusedWalk by remember(journey) { mutableStateOf<Leg?>(null) }
    var favorite by remember(journey, isFavorite) { mutableStateOf(isFavorite) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = { Text("Journey", fontWeight = FontWeight.Bold) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } },
            actions = {
                IconButton(onClick = { favorite = !favorite; onFavorite() }) {
                    Icon(if (favorite) Icons.Default.Star else Icons.Default.StarBorder, if (favorite) "Remove favourite" else "Favourite")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
        )

        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("${TimeFormat.time(journey.startTime)}–${TimeFormat.time(journey.endTime)}", fontSize = 23.sp, fontWeight = FontWeight.Black)
                Text("${journey.changes} change${if (journey.changes == 1) "" else "s"}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.weight(1f))
            Text(TimeFormat.duration(journey.durationMinutes), fontSize = 22.sp, fontWeight = FontWeight.Black)
        }

        Box(Modifier.fillMaxWidth().height(315.dp)) {
            NativeJourneyMap(journey, Modifier.fillMaxSize(), focusedWalk)
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.90f),
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.padding(12.dp).align(Alignment.TopStart)
            ) {
                Text(
                    if (focusedWalk == null) "MapLibre · Boreal route" else "Walking section",
                    Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            if (focusedWalk != null) {
                FilledTonalButton(
                    onClick = { focusedWalk = null },
                    modifier = Modifier.padding(12.dp).align(Alignment.BottomEnd)
                ) { Text("Show full journey") }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 34.dp)
        ) {
            itemsIndexed(legs, key = { index, leg -> "$index-${leg.startTime}-${leg.endTime}" }) { index, leg ->
                if (leg.effectiveMode() in setOf("WALK", "FOOT")) {
                    WalkingLegRow(leg, onFocus = { focusedWalk = leg })
                } else {
                    TransitLegRow(leg)
                }
                if (index != legs.lastIndex) {
                    TransferConnector(legs[index], legs[index + 1])
                }
            }
            item {
                ArrivalRow(legs.lastOrNull()?.to ?: Place("Destination"), journey.endTime)
            }
        }
    }
}

@Composable
fun RouteBadge(leg: Leg) {
    val label = leg.serviceLabel.ifBlank { leg.effectiveMode() }
    Surface(
        color = RouteColours.compose(leg),
        contentColor = RouteColours.textCompose(leg),
        shape = RoundedCornerShape(7.dp)
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            fontSize = 11.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1
        )
    }
}

@Composable
private fun WalkingLegRow(leg: Leg, onFocus: () -> Unit) {
    var expanded by remember(leg) { mutableStateOf(false) }
    val metres = leg.distanceMetres.toInt().coerceAtLeast(0)
    TimelineRow(icon = { Icon(Icons.Default.DirectionsWalk, null, tint = MaterialTheme.colorScheme.primary) }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Walk ${TimeFormat.duration(leg.durationMinutes)} · ${distanceText(metres)}", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text("${leg.from.name} → ${leg.to.name}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = onFocus) { Text("Map") }
        }
        Spacer(Modifier.height(8.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(13.dp),
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.DirectionsWalk, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (leg.walkingSteps.isEmpty()) "Walking directions" else "Walking directions · ${leg.walkingSteps.size} steps",
                    Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        AnimatedVisibility(expanded) {
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                if (leg.walkingSteps.isEmpty()) {
                    Text("Follow the mapped walking path to ${leg.to.name}.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                } else {
                    leg.walkingSteps.forEachIndexed { index, step ->
                        Text(
                            "${index + 1}. ${directionText(step.relativeDirection)}${step.streetName.takeIf { it.isNotBlank() }?.let { " onto $it" } ?: ""}${if (step.distanceMetres > 0) " · ${distanceText(step.distanceMetres.toInt())}" else ""}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TransitLegRow(leg: Leg) {
    TimelineRow(icon = { Icon(Icons.Default.DirectionsBus, null, tint = Color.White) }, iconBackground = RouteColours.compose(leg)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(leg.from.name, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                Spacer(Modifier.height(5.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RouteBadge(leg)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        leg.headsign.ifBlank { leg.to.name },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (leg.agencyName.isNotBlank()) {
                    Text(leg.agencyName, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
                }
            }
            Text(TimeFormat.time(leg.startTime), fontWeight = FontWeight.Bold, fontSize = 17.sp)
        }
        Spacer(Modifier.height(10.dp))
        val stopCount = leg.intermediateStops.size
        Text(
            "Ride${if (stopCount > 0) " $stopCount stop${if (stopCount == 1) "" else "s"}" else ""} · ${TimeFormat.duration(leg.durationMinutes)}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp
        )
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth()) {
            Text(leg.to.name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            Text(TimeFormat.time(leg.endTime), fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun TransferConnector(previous: Leg, next: Leg) {
    if (previous.to.name == next.from.name && next.effectiveMode() !in setOf("WALK", "FOOT")) {
        Row(Modifier.fillMaxWidth().padding(start = 42.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.TransferWithinAStation, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(7.dp))
            Text("Change", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        }
    }
}

@Composable
private fun ArrivalRow(place: Place, time: java.time.OffsetDateTime) {
    TimelineRow(icon = { Icon(Icons.Default.LocationOn, null, tint = Color.White) }, iconBackground = Color(0xFFD34C73), drawLine = false) {
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(place.name, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                Text("Arrive", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
            Text(TimeFormat.time(time), fontWeight = FontWeight.Bold, fontSize = 17.sp)
        }
    }
}

@Composable
private fun TimelineRow(
    icon: @Composable () -> Unit,
    iconBackground: Color? = null,
    drawLine: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    val resolvedIconBackground = iconBackground ?: MaterialTheme.colorScheme.surfaceVariant
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(42.dp)) {
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(resolvedIconBackground),
                contentAlignment = Alignment.Center
            ) { icon() }
            if (drawLine) Box(Modifier.width(3.dp).height(74.dp).background(MaterialTheme.colorScheme.primary))
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f), content = content)
    }
}

private fun distanceText(metres: Int): String = if (metres >= 1000) String.format(java.util.Locale.UK, "%.1f km", metres / 1000.0) else "$metres m"

private fun directionText(raw: String): String = when (raw.uppercase()) {
    "LEFT", "HARD_LEFT" -> "Turn left"
    "SLIGHTLY_LEFT" -> "Bear left"
    "RIGHT", "HARD_RIGHT" -> "Turn right"
    "SLIGHTLY_RIGHT" -> "Bear right"
    "CIRCLE_CLOCKWISE", "CIRCLE_COUNTERCLOCKWISE" -> "Use the roundabout"
    "UTURN_LEFT", "UTURN_RIGHT" -> "Make a U-turn"
    else -> "Continue"
}
