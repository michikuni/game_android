package com.example.game_android.game.core

class Camera { var x=0f; var y=0f; fun follow(px:Float,py:Float,vw:Int,vh:Int,ww:Float,wh:Float){ x=(px - vw/2f).coerceIn(0f, ww - vw); y=(py - vh/2f).coerceIn(0f, wh - vh) } }
