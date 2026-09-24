package com.tripshare.app

import android.app.Application
import com.tripshare.app.data.TripRepository

class TripShareApplication : Application() {
    lateinit var trips: TripRepository
        private set

    override fun onCreate() {
        super.onCreate()
        trips = TripRepository(this)
    }
}
