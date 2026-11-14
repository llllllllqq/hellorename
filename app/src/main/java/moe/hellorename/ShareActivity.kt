package moe.hellorename

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * 文件入口（VIEW / SEND / SEND_MULTIPLE）的转接组件，本身不显示界面。
 *
 * 为什么要把入口从 MainActivity 拆出来：HyperOS / MIUI 的分享面板在展示候选时，只列“专用文件
 * 处理组件”（SaveCopy 的 SaveActivity 就是 noHistory + excludeFromRecents + 无界面主题的形态）。
 * 入口挂在带桌面入口、有完整界面的 MainActivity 上时，系统能解析到，但面板里不展示。
 * 这里保持与 SaveActivity 相同的组件形态，收到的 Intent 原样转发给 MainActivity，
 * 界面与改名/保存逻辑都不变。
 */
class ShareActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 配置变更重建时不重复转发，否则同一份分享会被处理两次。
        if (savedInstanceState == null) {
            val forward = Intent(intent).setClass(this, MainActivity::class.java)
            val hasContentUri = intent.clipData != null || intent.data?.scheme == "content"
            if (hasContentUri) {
                // 同包转发本来就有效；显式带上标志，避免将来改成跨进程时静默丢授权。
                forward.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(forward)
        }
        finish()
    }
}
