package com.example.fitpulseai

import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory

internal fun appCheckProvider() = PlayIntegrityAppCheckProviderFactory.getInstance()
