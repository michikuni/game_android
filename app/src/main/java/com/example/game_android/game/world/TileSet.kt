package com.example.game_android.game.world

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.game_android.R

class TileSet(ctx: Context) {
    private val src = BitmapFactory.decodeResource(ctx.resources, R.drawable.decorate)
    private val tile = 32

    val grass = Bitmap.createBitmap(src, 6 * tile, 0, tile, tile)
    val block = Bitmap.createBitmap(src, 6 * tile, 3 * tile, tile, tile)
}