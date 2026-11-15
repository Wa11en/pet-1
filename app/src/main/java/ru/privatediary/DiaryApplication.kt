package ru.privatediary

import android.app.Application

class DiaryApplication : Application() {
    val graph by lazy { AppGraph(this) }
}
