package com.rnandroidhlsapp.downloader

import android.content.Context
import java.io.File

object StorageLocator {
    fun tempDir(context: Context): File {
        return context.getExternalFilesDir(null) ?: context.filesDir
    }
}
