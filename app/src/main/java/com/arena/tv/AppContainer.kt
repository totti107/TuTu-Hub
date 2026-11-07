package com.arena.tv

import android.content.Context
import com.arena.tv.data.ArenaDb
import com.arena.tv.data.ChannelFinder
import com.arena.tv.data.IptvRepository
import com.arena.tv.data.SettingsStore
import com.arena.tv.data.TraktRepository
import com.arena.tv.data.VodRepository
import com.arena.tv.sports.SportsApi
import com.arena.tv.sports.SportsRepository
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Simple manual dependency container, created once in [ArenaApp]. */
class AppContainer(context: Context) {
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    val db = ArenaDb.create(context)
    val settings = SettingsStore(context)
    val vod = VodRepository(http, db, settings)
    val stalker = com.arena.tv.data.StalkerClient(http)
    val iptv = IptvRepository(http, db, settings, vod, stalker, context.cacheDir)
    val streams = com.arena.tv.data.StreamResolver(settings, stalker)
    val trakt = TraktRepository(http, db, settings)
    val sports = SportsRepository(SportsApi(http))
    val finder = ChannelFinder(db, iptv, settings)
    val parental = com.arena.tv.data.ParentalLock(settings)
    val updater = com.arena.tv.data.Updater(context.applicationContext, http)
}
