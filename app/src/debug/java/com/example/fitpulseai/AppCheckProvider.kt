package com.example.fitpulseai

import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

internal fun appCheckProvider() = DebugAppCheckProviderFactory.getInstance()
