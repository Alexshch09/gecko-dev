/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.search

import androidx.lifecycle.Lifecycle.State.RESUMED
import androidx.navigation.NavController
import androidx.navigation.NavDirections
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import mozilla.components.browser.state.action.AwesomeBarAction
import mozilla.components.browser.state.action.AwesomeBarAction.EngagementFinished
import mozilla.components.browser.state.action.BrowserAction
import mozilla.components.browser.state.search.RegionState
import mozilla.components.browser.state.search.SearchEngine
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.SearchState
import mozilla.components.browser.state.state.selectedOrDefaultSearchEngine
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.compose.browser.toolbar.store.BrowserEditToolbarAction
import mozilla.components.compose.browser.toolbar.store.BrowserToolbarStore
import mozilla.components.concept.awesomebar.AwesomeBar.Suggestion
import mozilla.components.concept.awesomebar.AwesomeBar.SuggestionProvider
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.EngineSession.LoadUrlFlags
import mozilla.components.feature.tabs.TabsUseCases
import mozilla.components.support.test.libstate.ext.waitUntilIdle
import mozilla.components.support.test.middleware.CaptureActionsMiddleware
import mozilla.components.support.test.robolectric.testContext
import mozilla.telemetry.glean.testing.GleanTestRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.experiments.nimbus.NimbusEventStore
import org.mozilla.fenix.GleanMetrics.BookmarksManagement
import org.mozilla.fenix.GleanMetrics.Events
import org.mozilla.fenix.GleanMetrics.History
import org.mozilla.fenix.GleanMetrics.UnifiedSearch
import org.mozilla.fenix.R
import org.mozilla.fenix.browser.browsingmode.BrowsingModeManager
import org.mozilla.fenix.components.AppStore
import org.mozilla.fenix.components.NimbusComponents
import org.mozilla.fenix.components.appstate.AppState
import org.mozilla.fenix.components.search.BOOKMARKS_SEARCH_ENGINE_ID
import org.mozilla.fenix.components.usecases.FenixBrowserUseCases
import org.mozilla.fenix.ext.telemetryName
import org.mozilla.fenix.helpers.lifecycle.TestLifecycleOwner
import org.mozilla.fenix.search.SearchEngineSource.Bookmarks
import org.mozilla.fenix.search.SearchEngineSource.Shortcut
import org.mozilla.fenix.search.SearchFragmentAction.SearchProvidersUpdated
import org.mozilla.fenix.search.SearchFragmentAction.SearchShortcutEngineSelected
import org.mozilla.fenix.search.SearchFragmentAction.SearchStarted
import org.mozilla.fenix.search.SearchFragmentAction.SearchSuggestionsVisibilityUpdated
import org.mozilla.fenix.search.SearchFragmentAction.SuggestionClicked
import org.mozilla.fenix.search.SearchFragmentAction.SuggestionSelected
import org.mozilla.fenix.search.awesomebar.SearchSuggestionsProvidersBuilder
import org.mozilla.fenix.search.fixtures.EMPTY_SEARCH_FRAGMENT_STATE
import org.mozilla.fenix.utils.Settings
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FenixSearchMiddlewareTest {
    @get:Rule
    val gleanTestRule = GleanTestRule(testContext)

    private val engine: Engine = mockk {
        every { speculativeCreateSession(any(), any()) } just Runs
    }
    private val fenixBrowserUseCases: FenixBrowserUseCases = mockk(relaxed = true)
    private val browsingModeManager: BrowsingModeManager = mockk(relaxed = true)
    private val tabsUseCases: TabsUseCases = mockk()
    private val nimbusComponents: NimbusComponents = mockk()
    private val settings: Settings = mockk(relaxed = true)
    private val browserActionsCaptor = CaptureActionsMiddleware<BrowserState, BrowserAction>()
    private val searchActionsCaptor = CaptureActionsMiddleware<SearchFragmentState, SearchFragmentAction>()
    private val appStore: AppStore = mockk(relaxed = true)
    private var browserStore = BrowserStore(
        initialState = BrowserState(search = fakeSearchEnginesState()),
        middleware = listOf(browserActionsCaptor),
    )
    private val toolbarStore: BrowserToolbarStore = mockk(relaxed = true)
    private val navController: NavController = mockk(relaxed = true)

    @Test
    fun `WHEN the store is created THEN update the search engines configuration`() {
        val (_, store) = buildMiddlewareAndAddToSearchStore()

        assertNotNull(store.state.defaultEngine)
        assertEquals("Engine B", store.state.defaultEngine!!.name)
        assertTrue(store.state.areShortcutsAvailable)
        assertFalse(store.state.showSearchShortcuts)
        assertTrue(store.state.searchEngineSource is SearchEngineSource.Default)
        assertNotNull(store.state.searchEngineSource.searchEngine)
        assertEquals("Engine B", store.state.searchEngineSource.searchEngine!!.name)
    }

    @Test
    fun `WHEN search is started THEN warmup http engine and configure search providers`() {
        val defaultSearchEngine = fakeSearchEnginesState().selectedOrDefaultSearchEngine
        val preselectedSearchEngine = SearchEngine("engine-a", "Engine A", mockk(), type = SearchEngine.Type.BUNDLED)
        val isSearchInPrivateMode = true
        val (middleware, store) = buildMiddlewareAndAddToSearchStore()
        val expectedSuggestionProviders = setOf(mockk<SuggestionProvider>(), mockk<SuggestionProvider>())
        val expectedSearchSuggestionsProvider: SearchSuggestionsProvidersBuilder = mockk {
            every { getProvidersToAdd(any()) } returns expectedSuggestionProviders
        }
        every { middleware.buildSearchSuggestionsProvider() } returns expectedSearchSuggestionsProvider

        store.dispatch(SearchStarted(preselectedSearchEngine, isSearchInPrivateMode))

        verify { engine.speculativeCreateSession(isSearchInPrivateMode) }
        assertEquals(expectedSearchSuggestionsProvider, middleware.suggestionsProvidersBuilder)
        assertEquals(expectedSuggestionProviders.toList(), store.state.searchSuggestionsProviders.toList())
        assertEquals(Shortcut(preselectedSearchEngine), store.state.searchEngineSource)
        assertNotNull(store.state.defaultEngine)
        assertEquals(defaultSearchEngine?.id, store.state.defaultEngine?.id)
        assertFalse(store.state.shouldShowSearchSuggestions)
    }

    @Test
    fun `WHEN search is started with a preselected search engine THEN record telemetry`() {
        val preselectedSearchEngine = SearchEngine("engine-a", "Engine A", mockk(), type = SearchEngine.Type.BUNDLED)
        val (middleware, store) = buildMiddlewareAndAddToSearchStore()
        every { middleware.buildSearchSuggestionsProvider() } returns mockk(relaxed = true)

        store.dispatch(SearchStarted(preselectedSearchEngine, false))

        val telemetry = UnifiedSearch.engineSelected.testGetValue()
        assertEquals("engine_selected", telemetry?.get(0)?.name)
        assertEquals(preselectedSearchEngine.telemetryName(), telemetry?.get(0)?.extra?.get("engine"))
    }

    @Test
    fun `WHEN search is started with the default search engine then don't record telemetry`() {
        val (middleware, store) = buildMiddlewareAndAddToSearchStore()
        every { middleware.buildSearchSuggestionsProvider() } returns mockk(relaxed = true)

        store.dispatch(SearchStarted(null, false))

        assertNull(UnifiedSearch.engineSelected.testGetValue())
    }

    @Test
    fun `GIVEN should show recent searches and a query is set WHEN search is started THEN show search suggestions`() {
        val defaultSearchEngine = fakeSearchEnginesState().selectedOrDefaultSearchEngine
        val preselectedSearchEngine = SearchEngine("engine-a", "Engine A", mockk(), type = SearchEngine.Type.BUNDLED)
        val isSearchInPrivateMode = false
        every { settings.shouldShowRecentSearchSuggestions } returns true
        val (middleware, store) = buildMiddlewareAndAddToSearchStore()
        val expectedSuggestionProviders = setOf(mockk<SuggestionProvider>(), mockk<SuggestionProvider>())
        val expectedSearchSuggestionsProvider: SearchSuggestionsProvidersBuilder = mockk {
            every { getProvidersToAdd(any()) } returns expectedSuggestionProviders
        }
        every { middleware.buildSearchSuggestionsProvider() } returns expectedSearchSuggestionsProvider

        store.dispatch(SearchFragmentAction.UpdateQuery("test"))
        store.dispatch(SearchStarted(preselectedSearchEngine, isSearchInPrivateMode))

        verify { engine.speculativeCreateSession(isSearchInPrivateMode) }
        assertEquals(expectedSearchSuggestionsProvider, middleware.suggestionsProvidersBuilder)
        assertEquals(expectedSuggestionProviders.toList(), store.state.searchSuggestionsProviders.toList())
        assertEquals(Shortcut(preselectedSearchEngine), store.state.searchEngineSource)
        assertNotNull(store.state.defaultEngine)
        assertEquals(defaultSearchEngine?.id, store.state.defaultEngine?.id)
        assertTrue(store.state.shouldShowSearchSuggestions)
    }

    @Test
    fun `GIVEN should show shortcut suggestions and a query is set WHEN search is started THEN show search suggestions`() {
        val defaultSearchEngine = fakeSearchEnginesState().selectedOrDefaultSearchEngine!!
        val isSearchInPrivateMode = true
        every { settings.shouldShowShortcutSuggestions } returns true
        val (middleware, store) = buildMiddlewareAndAddToSearchStore()
        val expectedSuggestionProviders = setOf(mockk<SuggestionProvider>(), mockk<SuggestionProvider>())
        val expectedSearchSuggestionsProvider: SearchSuggestionsProvidersBuilder = mockk {
            every { getProvidersToAdd(any()) } returns expectedSuggestionProviders
        }
        every { middleware.buildSearchSuggestionsProvider() } returns expectedSearchSuggestionsProvider

        store.dispatch(SearchFragmentAction.UpdateQuery("test"))
        store.dispatch(SearchStarted(null, isSearchInPrivateMode))
        store.waitUntilIdle()

        verify { engine.speculativeCreateSession(isSearchInPrivateMode) }
        assertEquals(expectedSearchSuggestionsProvider, middleware.suggestionsProvidersBuilder)
        assertEquals(expectedSuggestionProviders.toList(), store.state.searchSuggestionsProviders.toList())
        assertEquals(defaultSearchEngine.id, store.state.searchEngineSource.searchEngine?.id)
        assertTrue(store.state.shouldShowSearchSuggestions)
    }

    @Test
    fun `GIVEN the search query is updated WHEN is is different than the current URL and not empty THEN show search suggestions`() {
        val (_, store) = buildMiddlewareAndAddToSearchStore()

        store.dispatch(SearchFragmentAction.UpdateQuery(store.state.url))
        assertFalse(store.state.shouldShowSearchSuggestions)

        store.dispatch(SearchFragmentAction.UpdateQuery("test"))
        assertTrue(store.state.shouldShowSearchSuggestions)

        store.dispatch(SearchFragmentAction.UpdateQuery(""))
        assertFalse(store.state.shouldShowSearchSuggestions)
    }

    @Test
    fun `GIVEN a search query already exists WHEN the search providers are updated THEN show new search suggestions`() {
        val (_, store) = buildMiddlewareAndAddToSearchStore()
        store.dispatch(SearchFragmentAction.UpdateQuery("test"))

        store.dispatch(SearchProvidersUpdated(listOf(mockk())))

        searchActionsCaptor.assertLastAction(SearchSuggestionsVisibilityUpdated::class) {
            assertTrue(it.visible)
        }
    }

    @Test
    fun `WHEN a new search engine is selected THEN update it in search state and record telemetry`() {
        val newSearchEngineSelection = SearchEngine(
            "engine-f", "Engine F", mockk(), type = SearchEngine.Type.BUNDLED_ADDITIONAL,
        )
        val appStore = AppStore(
            AppState(
                shortcutSearchEngine = newSearchEngineSelection,
            ),
        )
        val (middleware, store) = buildMiddlewareAndAddToSearchStore(appStore = appStore)
        val expectedSuggestionProviders = setOf(mockk<SuggestionProvider>(), mockk<SuggestionProvider>())
        val expectedSearchSuggestionsProvider: SearchSuggestionsProvidersBuilder = mockk {
            every { getProvidersToAdd(any()) } returns expectedSuggestionProviders
        }
        every { middleware.buildSearchSuggestionsProvider() } returns expectedSearchSuggestionsProvider

        store.dispatch(SearchStarted(null, false)) // this triggers observing the search engine updates

        searchActionsCaptor.assertLastAction(SearchShortcutEngineSelected::class) {
            assertEquals(newSearchEngineSelection, it.engine)
            assertFalse(it.browsingMode.isPrivate)
            assertEquals(settings, it.settings)
        }
        val telemetry = UnifiedSearch.engineSelected.testGetValue()
        assertEquals("engine_selected", telemetry?.get(0)?.name)
        assertEquals(newSearchEngineSelection.telemetryName(), telemetry?.get(0)?.extra?.get("engine"))
    }

    @Test
    fun `When needing to load an URL THEN open it in browser, record search ended and record telemetry`() {
        val url = "https://mozilla.com"
        val flags = LoadUrlFlags.all()
        every { settings.enableHomepageAsNewTab } returns true
        val (middleware, _) = buildMiddlewareAndAddToSearchStore()

        middleware.loadUrlUseCase.invoke(url, flags, null, null)

        verify { navController.navigate(R.id.browserFragment) }
        verify {
            fenixBrowserUseCases.loadUrlOrSearch(
                searchTermOrURL = url,
                newTab = false,
                private = false,
                flags = flags,
            )
        }
        browserActionsCaptor.assertLastAction(EngagementFinished::class) {
            assertEquals(false, it.abandoned)
        }
        val telemetry = Events.enteredUrl.testGetValue()
        assertEquals("entered_url", telemetry?.get(0)?.name)
        assertEquals("false", telemetry?.get(0)?.extra?.get("autocomplete"))
    }

    @Test
    fun `WHEN needing to search for specific terms THEN open them in browser, record search ended and record telemetry`() {
        val searchTerm = "test"
        every { settings.enableHomepageAsNewTab } returns true
        val nimbusEventsStore: NimbusEventStore = mockk {
            every { recordEvent(any()) } just Runs
        }
        every { nimbusComponents.events } returns nimbusEventsStore
        val middleware = buildMiddleware(nimbusComponents = nimbusComponents)
        val store = SearchFragmentStore(
            initialState = buildEmptySearchState(),
            middleware = listOf(middleware),
        )

        middleware.searchUseCase.invoke(searchTerm, null, null)

        verify { navController.navigate(R.id.browserFragment) }
        verify {
            fenixBrowserUseCases.loadUrlOrSearch(
                searchTermOrURL = searchTerm,
                newTab = false,
                private = false,
                forceSearch = true,
                searchEngine = store.state.searchEngineSource.searchEngine,
                flags = LoadUrlFlags.none(),
            )
        }
        browserActionsCaptor.assertLastAction(EngagementFinished::class) {
            assertEquals(false, it.abandoned)
        }
        verify { nimbusEventsStore.recordEvent("performed_search") }
        val telemetry = Events.performedSearch.testGetValue()
        assertEquals("performed_search", telemetry?.get(0)?.name)
        assertEquals("default.suggestion", telemetry?.get(0)?.extra?.get("source"))
    }

    @Test
    fun `WHEN needing to select a specific tab THEN open it in browser and record search ended`() {
        val selectedTabId = "tab2"
        val tabsUseCases: TabsUseCases = mockk(relaxed = true)
        val (middleware, _) = buildMiddlewareAndAddToSearchStore(tabsUseCases = tabsUseCases)

        middleware.selectTabUseCase.invoke(selectedTabId)

        verify { tabsUseCases.selectTab(selectedTabId) }
        verify { navController.navigate(R.id.browserFragment) }
        browserActionsCaptor.assertLastAction(EngagementFinished::class) {
            assertEquals(false, it.abandoned)
        }
    }

    @Test
    fun `WHEN the user selects a specific search engine THEN update the search engine to be used for future searches and record telemetry`() {
        val defaultSearchEngine = fakeSearchEnginesState().selectedOrDefaultSearchEngine
        val searchEngineClicked = SearchEngine(
            id = BOOKMARKS_SEARCH_ENGINE_ID,
            name = "Bookmarks",
            icon = mockk(),
            type = SearchEngine.Type.APPLICATION,
        )
        val expectedSuggestionProviders = setOf(mockk<SuggestionProvider>(), mockk<SuggestionProvider>())
        val expectedSearchSuggestionsProvider: SearchSuggestionsProvidersBuilder = mockk {
            every { getProvidersToAdd(any()) } returns expectedSuggestionProviders
        }
        val (middleware, store) = buildMiddlewareAndAddToSearchStore()
        middleware.suggestionsProvidersBuilder = expectedSearchSuggestionsProvider

        middleware.handleSearchShortcutEngineSelectedByUser(searchEngineClicked)

        assertEquals(expectedSearchSuggestionsProvider, middleware.suggestionsProvidersBuilder)
        assertEquals(expectedSuggestionProviders.toList(), store.state.searchSuggestionsProviders.toList())
        assertEquals(Bookmarks(searchEngineClicked), store.state.searchEngineSource)
        assertNotNull(store.state.defaultEngine)
        assertEquals(defaultSearchEngine?.id, store.state.defaultEngine?.id)
        browserActionsCaptor.assertNotDispatched(EngagementFinished::class)
        val telemetry = UnifiedSearch.engineSelected.testGetValue()?.firstOrNull()
        assertEquals("engine_selected", telemetry?.name)
        assertEquals("bookmarks", telemetry?.extra?.get("engine"))
    }

    @Test
    fun `GIVEN search settings are clicked WHEN handling this THEN open the settings screen and record search ended`() {
        every { navController.navigate(any<NavDirections>()) } just Runs
        every { navController.currentDestination } returns mockk {
            every { id } returns R.id.searchDialogFragment
        }
        val (middleware, _) = buildMiddlewareAndAddToSearchStore()

        middleware.handleClickSearchEngineSettings()

        verify { navController.navigate(SearchDialogFragmentDirections.actionGlobalSearchEngineFragment()) }
        browserActionsCaptor.assertLastAction(EngagementFinished::class) {
            assertEquals(true, it.abandoned)
        }
    }

    @Test
    fun `WHEN a search suggestion is clicked THEN exit search mode and execute the custom actions for it`() {
        var wasSuggestionClickHandled = false
        val customSuggestionClickedAction = { wasSuggestionClickHandled = true }
        val clickedSuggestion = Suggestion(provider = mockk(), onSuggestionClicked = customSuggestionClickedAction)
        val (_, store) = buildMiddlewareAndAddToSearchStore()

        store.dispatch(SuggestionClicked(clickedSuggestion))

        assertTrue(wasSuggestionClickHandled)
        verify { toolbarStore.dispatch(BrowserEditToolbarAction.SearchQueryUpdated("")) }
        browserActionsCaptor.assertLastAction(AwesomeBarAction.SuggestionClicked::class) {
            assertEquals(clickedSuggestion, it.suggestion)
        }
    }

    @Test
    fun `GIVEN the search selector menu is opened WHEN the history search engine item is clicked THEN record telemetry`() {
        val historySuggestion: Suggestion = mockk(relaxed = true) {
            every { flags } returns setOf(Suggestion.Flag.HISTORY)
        }
        val (_, store) = buildMiddlewareAndAddToSearchStore()

        store.dispatch(SuggestionClicked(historySuggestion))

        assertNotNull(History.searchResultTapped.testGetValue())
    }

    @Test
    fun `GIVEN the search selector menu is opened WHEN the bookmarks search engine item is clicked THEN record telemetry`() {
        val bookmarksSuggestion: Suggestion = mockk(relaxed = true) {
            every { flags } returns setOf(Suggestion.Flag.BOOKMARK)
        }
        val (_, store) = buildMiddlewareAndAddToSearchStore()

        store.dispatch(SuggestionClicked(bookmarksSuggestion))

        assertNotNull(BookmarksManagement.searchResultTapped.testGetValue())
    }

    @Test
    fun `WHEN a search suggestion is selected for edit THEN update the current search query with the search suggestion text`() {
        val selectedSuggestion = Suggestion(provider = mockk(), editSuggestion = "test")
        val (_, store) = buildMiddlewareAndAddToSearchStore()

        store.dispatch(SuggestionSelected(selectedSuggestion))

        verify { toolbarStore.dispatch(BrowserEditToolbarAction.SearchQueryUpdated("test")) }
    }

    private fun buildMiddlewareAndAddToSearchStore(
        engine: Engine = this.engine,
        tabsUseCases: TabsUseCases = this.tabsUseCases,
        settings: Settings = this.settings,
        appStore: AppStore = this.appStore,
        browserStore: BrowserStore = this.browserStore,
        toolbarStore: BrowserToolbarStore = this.toolbarStore,
    ): Pair<FenixSearchMiddleware, SearchFragmentStore> {
        val middleware = spyk(
            buildMiddleware(
                engine, tabsUseCases, nimbusComponents, settings, appStore, browserStore, toolbarStore,
            ),
        )
        val store = SearchFragmentStore(
            initialState = buildEmptySearchState(),
            middleware = listOf(middleware, searchActionsCaptor),
        )
        store.waitUntilIdle()
        return middleware to store
    }

    private fun buildMiddleware(
        engine: Engine = this.engine,
        tabsUseCases: TabsUseCases = this.tabsUseCases,
        nimbusComponents: NimbusComponents = this.nimbusComponents,
        settings: Settings = this.settings,
        appStore: AppStore = this.appStore,
        browserStore: BrowserStore = this.browserStore,
        toolbarStore: BrowserToolbarStore = this.toolbarStore,
    ) = FenixSearchMiddleware(
        engine = engine,
        tabsUseCases = tabsUseCases,
        nimbusComponents = nimbusComponents,
        settings = settings,
        appStore = appStore,
        browserStore = browserStore,
        toolbarStore = toolbarStore,
    ).apply {
        updateLifecycleDependencies(
            FenixSearchMiddleware.LifecycleDependencies(
                context = testContext,
                lifecycleOwner = TestLifecycleOwner(RESUMED),
                browsingModeManager = browsingModeManager,
                navController = navController,
                fenixBrowserUseCases = fenixBrowserUseCases,
            ),
        )
    }

    private fun buildEmptySearchState(
        searchEngineSource: SearchEngineSource = SearchEngineSource.Default(searchEngine = mockk()),
        defaultEngine: SearchEngine? = mockk(),
        areShortcutsAvailable: Boolean = true,
        showSearchShortcutsSetting: Boolean = false,
        showHistorySuggestionsForCurrentEngine: Boolean = true,
        showSponsoredSuggestions: Boolean = true,
        showNonSponsoredSuggestions: Boolean = true,
    ): SearchFragmentState = EMPTY_SEARCH_FRAGMENT_STATE.copy(
        searchEngineSource = searchEngineSource,
        defaultEngine = defaultEngine,
        showSearchShortcutsSetting = showSearchShortcutsSetting,
        areShortcutsAvailable = areShortcutsAvailable,
        showSearchTermHistory = true,
        showHistorySuggestionsForCurrentEngine = showHistorySuggestionsForCurrentEngine,
        showSponsoredSuggestions = showSponsoredSuggestions,
        showNonSponsoredSuggestions = showNonSponsoredSuggestions,
        showQrButton = true,
    )

    private fun fakeSearchEnginesState() = SearchState(
        region = RegionState("US", "US"),
        regionSearchEngines = listOf(
            SearchEngine("engine-a", "Engine A", mockk(), type = SearchEngine.Type.BUNDLED),
            SearchEngine("engine-b", "Engine B", mockk(), type = SearchEngine.Type.BUNDLED),
            SearchEngine("engine-c", "Engine C", mockk(), type = SearchEngine.Type.BUNDLED),
        ),
        customSearchEngines = listOf(
            SearchEngine("engine-d", "Engine D", mockk(), type = SearchEngine.Type.CUSTOM),
            SearchEngine("engine-e", "Engine E", mockk(), type = SearchEngine.Type.CUSTOM),
        ),
        additionalSearchEngines = listOf(
            SearchEngine("engine-f", "Engine F", mockk(), type = SearchEngine.Type.BUNDLED_ADDITIONAL),
        ),
        additionalAvailableSearchEngines = listOf(
            SearchEngine("engine-g", "Engine G", mockk(), type = SearchEngine.Type.BUNDLED_ADDITIONAL),
            SearchEngine("engine-h", "Engine H", mockk(), type = SearchEngine.Type.BUNDLED_ADDITIONAL),
        ),
        hiddenSearchEngines = listOf(
            SearchEngine("engine-i", "Engine I", mockk(), type = SearchEngine.Type.BUNDLED),
        ),
        regionDefaultSearchEngineId = "engine-b",
        userSelectedSearchEngineId = null,
        userSelectedSearchEngineName = null,
    )
}
