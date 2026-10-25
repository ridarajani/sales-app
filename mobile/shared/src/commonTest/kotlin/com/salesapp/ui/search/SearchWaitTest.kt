package com.salesapp.ui.search

import com.salesapp.core.KeyValueStore
import com.salesapp.data.AppState
import com.salesapp.data.SearchGroup
import com.salesapp.data.SearchItem
import com.salesapp.data.SearchResult
import com.salesapp.data.SearchSource
import com.salesapp.data.SessionStore
import com.salesapp.data.User
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

// The search box is typed into letter by letter on a phone with little signal, so it must ask the
// server once, when the typing stops, and never show an older answer than the words on screen.
@OptIn(ExperimentalCoroutinesApi::class)
class SearchWaitTest {
    // Every later answer comes back quicker than the one before, so an earlier one lands last
    // unless its job was cancelled. It echoes back what it was asked for.
    private class SlowFirstSearch : SearchSource {
        val asked = mutableListOf<String>()

        override suspend fun search(q: String): SearchResult {
            asked += q
            delay(1000L / asked.size)
            return SearchResult(
                q = q,
                groups = listOf(SearchGroup(key = "customers", title = "Customers", count = 1, items = listOf(SearchItem(1, q)))),
            )
        }
    }

    private fun appState() = AppState(KeyValueStore(), SessionStore(KeyValueStore())).apply {
        user = User(id = 1, name = "Mary Banda", phone = "0991234567", role = "admin", companies = listOf("LV", "ZT"))
    }

    @Test
    fun typingAWholeWordAsksTheServerOnce() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val source = SlowFirstSearch()
            val model = SearchViewModel(source, appState())

            // Four letters typed quickly, each inside the wait.
            for (value in listOf("a", "ar", "are", "area")) {
                model.onEvent(SearchEvent.QueryChanged(value))
                advanceTimeBy(100)
            }
            advanceUntilIdle()

            assertEquals(listOf("area"), source.asked)
            assertEquals("area", model.state.value.groups.first().items.first().title)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun underTwoLettersAsksNothingAtAll() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val source = SlowFirstSearch()
            val model = SearchViewModel(source, appState())

            model.onEvent(SearchEvent.QueryChanged("a"))
            advanceUntilIdle()
            assertEquals(emptyList(), source.asked)
            assertTrue(model.state.value.tooShort)

            // Two letters is enough, and taking one away again clears the list.
            model.onEvent(SearchEvent.QueryChanged("ar"))
            advanceUntilIdle()
            assertEquals(listOf("ar"), source.asked)
            assertEquals(1, model.state.value.groups.size)

            model.onEvent(SearchEvent.QueryChanged("a"))
            advanceUntilIdle()
            assertEquals(listOf("ar"), source.asked)
            assertTrue(model.state.value.groups.isEmpty())
            assertTrue(model.state.value.tooShort)
        } finally {
            Dispatchers.resetMain()
        }
    }

    // The bug this guards: the slower answer to an earlier word landing last, so the list belongs
    // to words the person has already changed.
    @Test
    fun anAnswerForOlderWordsIsNeverShown() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val source = SlowFirstSearch()
            val model = SearchViewModel(source, appState())

            // The first search is on its way (it takes 1000ms).
            model.onEvent(SearchEvent.QueryChanged("salima"))
            advanceTimeBy(400)
            assertEquals(listOf("salima"), source.asked)

            // The person types something else; this answer takes only 500ms.
            model.onEvent(SearchEvent.QueryChanged("nkhotakota"))
            advanceUntilIdle()

            assertEquals(listOf("salima", "nkhotakota"), source.asked)
            assertEquals("nkhotakota", model.state.value.groups.first().items.first().title)
            assertEquals("nkhotakota", model.state.value.query)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
