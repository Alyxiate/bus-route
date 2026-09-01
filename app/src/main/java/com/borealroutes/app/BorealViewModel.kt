package com.borealroutes.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime


data class BorealUiState(
    val fromText: String = "",
    val toText: String = "",
    val fromPlace: Place? = null,
    val toPlace: Place? = null,
    val viaText: String = "",
    val viaPlace: Place? = null,
    val dateIso: String = LocalDate.now().toString(),
    val time: String = LocalTime.now().withSecond(0).withNano(0).toString().take(5),
    val timeMode: TimeMode = TimeMode.LEAVE_AT,
    val includeCoaches: Boolean = false,
    val includeTrains: Boolean = false,
    val includeFerries: Boolean = false,
    val includePlanes: Boolean = false,
    val avoid: String = "",
    val dayTrip: Boolean = false,
    val breakMinutes: Int = 0,
    val sortPreference: SortPreference = SortPreference.FASTEST,
    val searching: Boolean = false,
    val status: String = "Ready",
    val journeys: List<Itinerary> = emptyList(),
    val recentJourneys: List<Itinerary> = emptyList(),
    val favoriteJourneys: List<Itinerary> = emptyList(),
    val rawScanned: Int = 0,
    val slicesDone: Int = 0,
    val selectedJourney: Itinerary? = null,
    val fromSuggestions: List<Place> = emptyList(),
    val toSuggestions: List<Place> = emptyList(),
    val viaSuggestions: List<Place> = emptyList(),
    val error: String? = null
)

class BorealViewModel(application: Application) : AndroidViewModel(application) {
    private val routing = RoutingEngine()
    private val prefs = application.getSharedPreferences("boreal_native", 0)
    private val _state = MutableStateFlow(loadInitialState())
    val state: StateFlow<BorealUiState> = _state.asStateFlow()

    private var searchJob: Job? = null
    private var fromSuggestJob: Job? = null
    private var toSuggestJob: Job? = null
    private var viaSuggestJob: Job? = null

    fun updateFrom(text: String) {
        _state.value = _state.value.copy(fromText = text, fromPlace = null)
        fromSuggestJob?.cancel()
        fromSuggestJob = suggestionJob(text) { _state.value = _state.value.copy(fromSuggestions = it) }
    }

    fun updateTo(text: String) {
        _state.value = _state.value.copy(toText = text, toPlace = null)
        toSuggestJob?.cancel()
        toSuggestJob = suggestionJob(text) { _state.value = _state.value.copy(toSuggestions = it) }
    }

    fun updateVia(text: String) {
        _state.value = _state.value.copy(viaText = text, viaPlace = null)
        viaSuggestJob?.cancel()
        viaSuggestJob = suggestionJob(text) { _state.value = _state.value.copy(viaSuggestions = it) }
    }

    private fun suggestionJob(text: String, apply: (List<Place>) -> Unit): Job = viewModelScope.launch {
        if (text.trim().length < 2) { apply(emptyList()); return@launch }
        delay(250)
        runCatching { Network.photon.suggest(text) }.onSuccess(apply).onFailure { apply(emptyList()) }
    }

    fun chooseFrom(place: Place) {
        _state.value = _state.value.copy(
            fromText = display(place), fromPlace = place, fromSuggestions = emptyList()
        )
    }

    fun chooseTo(place: Place) {
        _state.value = _state.value.copy(
            toText = display(place), toPlace = place, toSuggestions = emptyList()
        )
    }

    fun chooseVia(place: Place) {
        _state.value = _state.value.copy(
            viaText = display(place), viaPlace = place, viaSuggestions = emptyList()
        )
    }

    fun swap() {
        val s = _state.value
        _state.value = s.copy(
            fromText = s.toText, toText = s.fromText,
            fromPlace = s.toPlace, toPlace = s.fromPlace,
            fromSuggestions = emptyList(), toSuggestions = emptyList()
        )
    }

    fun setDate(date: String) { _state.value = _state.value.copy(dateIso = date) }
    fun setTime(time: String) { _state.value = _state.value.copy(time = time) }
    fun setTimeMode(mode: TimeMode) { _state.value = _state.value.copy(timeMode = mode) }
    fun setCoaches(v: Boolean) { _state.value = _state.value.copy(includeCoaches = v) }
    fun setTrains(v: Boolean) { _state.value = _state.value.copy(includeTrains = v) }
    fun setFerries(v: Boolean) { _state.value = _state.value.copy(includeFerries = v) }
    fun setPlanes(v: Boolean) { _state.value = _state.value.copy(includePlanes = v) }
    fun setAvoid(v: String) { _state.value = _state.value.copy(avoid = v) }
    fun setDayTrip(v: Boolean) { _state.value = _state.value.copy(dayTrip = v) }
    fun setBreakMinutes(v: Int) { _state.value = _state.value.copy(breakMinutes = v.coerceIn(0, 480)) }
    fun setSort(pref: SortPreference) {
        val sorted = _state.value.journeys.sortedWith { a, b ->
            JourneyLogic.compare(a, b, pref, _state.value.timeMode == TimeMode.ARRIVE_BY)
        }
        _state.value = _state.value.copy(sortPreference = pref, journeys = sorted)
    }

    fun setCurrentAsFrom(point: GeoPoint) {
        val p = Place("Current location", point)
        _state.value = _state.value.copy(fromText = "Current location", fromPlace = p, fromSuggestions = emptyList())
    }

    fun setCurrentAsTo(point: GeoPoint) {
        val p = Place("Current location", point)
        _state.value = _state.value.copy(toText = "Current location", toPlace = p, toSuggestions = emptyList())
    }

    fun search() {
        val s = _state.value
        if (s.fromText.isBlank() || s.toText.isBlank()) {
            _state.value = s.copy(error = "Enter both a start and destination.")
            return
        }
        searchJob?.cancel()
        SearchForegroundService.start(getApplication())
        _state.value = s.copy(searching = true, status = "Starting selected-day nationwide search…", journeys = emptyList(), error = null)
        searchJob = viewModelScope.launch {
            try {
                val req = SearchRequest(
                    fromText = _state.value.fromText,
                    toText = _state.value.toText,
                    fromPlace = _state.value.fromPlace,
                    toPlace = _state.value.toPlace,
                    dateIso = _state.value.dateIso,
                    time = _state.value.time,
                    timeMode = _state.value.timeMode,
                    options = SearchOptions(
                        includeCoaches = _state.value.includeCoaches,
                        includeTrains = _state.value.includeTrains,
                        includeFerries = _state.value.includeFerries,
                        includePlanes = _state.value.includePlanes,
                        viaText = _state.value.viaText,
                        viaPlace = _state.value.viaPlace,
                        avoidTerms = _state.value.avoid,
                        dayTrip = _state.value.dayTrip,
                        breakMinutes = _state.value.breakMinutes,
                        sortPreference = _state.value.sortPreference
                    )
                )
                val final = routing.search(req) { p ->
                    val sorted = p.journeys.sortedWith { a, b ->
                        JourneyLogic.compare(a, b, _state.value.sortPreference, _state.value.timeMode == TimeMode.ARRIVE_BY)
                    }
                    _state.value = _state.value.copy(
                        status = p.message, journeys = sorted,
                        rawScanned = p.rawScanned, slicesDone = p.slicesDone
                    )
                }
                val sorted = final.sortedWith { a, b ->
                    JourneyLogic.compare(a, b, _state.value.sortPreference, _state.value.timeMode == TimeMode.ARRIVE_BY)
                }
                _state.value = _state.value.copy(
                    searching = false,
                    journeys = sorted,
                    status = if (sorted.isEmpty()) "No route found on the selected day." else "Search complete — ${sorted.size} valid journey option${if (sorted.size == 1) "" else "s"}."
                )
                saveLastSearch()
                saveRecentResults(sorted.take(20))
                _state.value = _state.value.copy(recentJourneys = mergeUnique(sorted.take(20), _state.value.recentJourneys).take(40))
            } catch (t: Throwable) {
                _state.value = _state.value.copy(searching = false, error = t.message ?: "Search failed", status = "Search failed")
            } finally {
                SearchForegroundService.stop(getApplication())
            }
        }
    }

    fun cancelSearch() {
        searchJob?.cancel()
        SearchForegroundService.stop(getApplication())
        _state.value = _state.value.copy(searching = false, status = "Search cancelled")
    }

    fun selectJourney(itinerary: Itinerary?) { _state.value = _state.value.copy(selectedJourney = itinerary) }
    fun clearError() { _state.value = _state.value.copy(error = null) }

    fun favorite(itinerary: Itinerary) {
        val key = JourneyLogic.itineraryKey(itinerary)
        val current = favoriteKeys().toMutableSet()
        val added = current.add(key)
        if (!added) {
            current.remove(key)
            prefs.edit().remove("favorite.$key").apply()
        } else if (itinerary.rawJson.isNotBlank()) {
            prefs.edit().putString("favorite.$key", itinerary.rawJson).apply()
        }
        prefs.edit().putStringSet("favorite_keys", current).apply()
        val favorites = if (added) mergeUnique(listOf(itinerary), _state.value.favoriteJourneys)
            else _state.value.favoriteJourneys.filterNot { JourneyLogic.itineraryKey(it) == key }
        _state.value = _state.value.copy(favoriteJourneys = favorites)
    }

    fun isFavorite(itinerary: Itinerary): Boolean = favoriteKeys().contains(JourneyLogic.itineraryKey(itinerary))

    private fun favoriteKeys(): Set<String> = prefs.getStringSet("favorite_keys", emptySet()) ?: emptySet()

    private fun display(p: Place) = p.name + if (p.subtitle.isNotBlank()) ", ${p.subtitle}" else ""

    private fun saveLastSearch() {
        val s = _state.value
        prefs.edit()
            .putString("from", s.fromText)
            .putString("to", s.toText)
            .putString("via", s.viaText)
            .putString("date", s.dateIso)
            .putString("time", s.time)
            .putString("timeMode", s.timeMode.name)
            .apply()
    }

    private fun saveRecentResults(items: List<Itinerary>) {
        val arr = JSONArray()
        items.forEach { if (it.rawJson.isNotBlank()) arr.put(JSONObject(it.rawJson)) }
        prefs.edit().putString("recent_results", arr.toString()).apply()
    }

    private fun loadInitialState(): BorealUiState {
        val recent = parseStoredRecent()
        val favorites = favoriteKeys().mapNotNull { key ->
            prefs.getString("favorite.$key", null)?.let(Network.transitous::parseSavedItinerary)
        }
        return BorealUiState(
            fromText = prefs.getString("from", "").orEmpty(),
            toText = prefs.getString("to", "").orEmpty(),
            viaText = prefs.getString("via", "").orEmpty(),
            dateIso = prefs.getString("date", LocalDate.now().toString()).orEmpty(),
            time = prefs.getString("time", LocalTime.now().withSecond(0).withNano(0).toString().take(5)).orEmpty(),
            timeMode = runCatching { TimeMode.valueOf(prefs.getString("timeMode", TimeMode.LEAVE_AT.name)!!) }.getOrDefault(TimeMode.LEAVE_AT),
            recentJourneys = recent,
            favoriteJourneys = favorites
        )
    }

    private fun parseStoredRecent(): List<Itinerary> {
        val raw = prefs.getString("recent_results", "[]").orEmpty()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                Network.transitous.parseSavedItinerary(obj.toString())?.let(::add)
            }
        }
    }

    private fun mergeUnique(first: List<Itinerary>, second: List<Itinerary>): List<Itinerary> {
        val seen = mutableSetOf<String>()
        return (first + second).filter { seen.add(JourneyLogic.itineraryKey(it)) }
    }
}
