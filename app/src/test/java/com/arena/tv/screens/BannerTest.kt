package com.arena.tv.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.arena.tv.R
import org.junit.Rule
import org.junit.Test

class BannerTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6)
    @Test fun banner() = paparazzi.snapshot { Image(painterResource(R.drawable.tv_banner), null, Modifier.size(320.dp, 180.dp)) }
}
