package com.colorwalk.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class ColorWalkApp : Application(), ImageLoaderFactory {

    // Coil calls newImageLoader() multiple times; lazily cache the single instance.
    private val coilImageLoader: ImageLoader by lazy {
        ImageLoader.Builder(this)
            .crossfade(true)
            .build()
    }

    override fun newImageLoader(): ImageLoader = coilImageLoader
}
