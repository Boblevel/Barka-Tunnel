package com.barkatunnel.app.ipfinder.assistant

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

class BarkaRecognitionService : RecognitionService() {

    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        listener.error(SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onStopListening(listener: Callback) = Unit

    override fun onCancel(listener: Callback) = Unit
}
