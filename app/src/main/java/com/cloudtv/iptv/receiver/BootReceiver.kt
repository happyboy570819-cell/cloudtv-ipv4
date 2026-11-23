package com.cloudtv.iptv.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.cloudtv.iptv.repository.ChannelRepository
import com.cloudtv.iptv.ui.MainActivity

/**
 * 电视盒子开机自启广播接收器
 * 当用户在设置面板开启“开机自动启动直播”后，电视盒子通电开机即直接进入上次观看的电视频道。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == "android.intent.action.QUICKBOOT_POWERON") {
            val repo = ChannelRepository(context)
            if (repo.bootAutoStart) {
                val launchIntent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                context.startActivity(launchIntent)
            }
        }
    }
}
