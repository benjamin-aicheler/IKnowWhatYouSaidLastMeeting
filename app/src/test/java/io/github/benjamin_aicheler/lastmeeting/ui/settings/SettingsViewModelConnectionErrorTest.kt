package io.github.benjamin_aicheler.lastmeeting.ui.settings

import android.app.Application
import io.github.benjamin_aicheler.lastmeeting.R
import io.github.benjamin_aicheler.lastmeeting.data.api.ApiHttpException
import io.github.benjamin_aicheler.lastmeeting.data.api.UnexpectedModelsResponseException
import io.github.benjamin_aicheler.lastmeeting.data.api.UnsupportedModelsEndpointException
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class SettingsViewModelConnectionErrorTest {

    @Test
    fun formatConnectionTestFailureDistinguishesErrorCategories() {
        val context = RuntimeEnvironment.getApplication()

        assertEquals(
            context.getString(R.string.msg_connection_test_models_unsupported, 404),
            formatConnectionTestFailure(context, UnsupportedModelsEndpointException(404, "Not found"))
        )
        assertEquals(
            context.getString(R.string.msg_connection_test_unexpected_response),
            formatConnectionTestFailure(context, UnexpectedModelsResponseException("HTML landing page"))
        )
        assertEquals(
            context.getString(R.string.msg_connection_test_auth_failed, 401),
            formatConnectionTestFailure(context, ApiHttpException(401, "Invalid key"))
        )
        assertEquals(
            context.getString(R.string.msg_connection_test_auth_failed, 403),
            formatConnectionTestFailure(context, ApiHttpException(403, "Forbidden"))
        )
        assertEquals(
            context.getString(R.string.msg_connection_test_failed, "Bad gateway"),
            formatConnectionTestFailure(context, ApiHttpException(502, "Bad gateway"))
        )
        assertEquals(
            context.getString(R.string.msg_connection_test_network_failed, "DNS lookup failed"),
            formatConnectionTestFailure(context, IOException("DNS lookup failed"))
        )
    }
}
