package io.github.benjamin_aicheler.lastmeeting.ui

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.benjamin_aicheler.lastmeeting.navigateFromIfCurrentAndResumed
import io.github.benjamin_aicheler.lastmeeting.navigateUpFromEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavHostGuardTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun duplicateForwardAndBackCallbacksRespectEntryStateAndPreserveRoot() {
        lateinit var navController: NavHostController
        lateinit var mainEntry: NavBackStackEntry
        var detailsEntry: NavBackStackEntry? = null

        compose.setContent {
            navController = rememberNavController()
            NavHost(navController = navController, startDestination = "main") {
                composable("main") { entry ->
                    mainEntry = entry
                }
                composable("details/1") { entry ->
                    detailsEntry = entry
                }
            }
        }

        compose.runOnIdle {
            // Duplicate forward taps from main push at most one details destination.
            assertTrue(navController.navigateFromIfCurrentAndResumed(mainEntry, "details/1"))
            assertFalse(navController.navigateFromIfCurrentAndResumed(mainEntry, "details/1"))
        }

        compose.waitUntil(5_000) {
            detailsEntry != null && navController.currentDestination?.route == "details/1"
        }

        compose.runOnIdle {
            val currentDetails = requireNotNull(detailsEntry)
            // Rapid double Back / delete-racing-Back pops only details and keeps main as root.
            assertTrue(navController.navigateUpFromEntry(currentDetails))
            assertFalse(navController.navigateUpFromEntry(currentDetails))
            assertFalse(navController.navigateUpFromEntry(mainEntry))
            assertEquals("main", navController.currentDestination?.route)
        }
    }
}
