package com.example.game_android.game.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
object SpriteLoader { fun loadSet(ctx: Context, prefix:String, count:Int): List<Bitmap>?{ val res= mutableListOf<Bitmap>(); val r=ctx.resources; for(i in 0 until count){ val id=r.getIdentifier(prefix+i, "drawable", ctx.packageName); if(id==0) return null; res.add(BitmapFactory.decodeResource(r,id)) }; return res } }
