package com.vibe.assistant.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.vibe.assistant.access.ScreenProjector
import com.vibe.assistant.service.VibeService

/**
 * Invisible screen that only shows Android's own "Start recording / sharing?"
 * question for screen capture, then hands the answer to Vibe's service.
 */
class ProjectionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(ScreenProjector.consentIntent(this), 77)
        } catch (_: Exception) {
            VibeService.send(this, VibeService.A_PROJECTION_DENIED)
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 77 && resultCode == RESULT_OK && data != null) {
            ScreenProjector.resultCode = resultCode
            ScreenProjector.resultData = data
            VibeService.send(this, VibeService.A_PROJECTION_READY)
        } else {
            VibeService.send(this, VibeService.A_PROJECTION_DENIED)
        }
        finish()
    }
}
