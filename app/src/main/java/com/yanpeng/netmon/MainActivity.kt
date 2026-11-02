package com.yanpeng.netmon

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

class MainActivity : AppCompatActivity() {

    private lateinit var pager: ViewPager2
    private var pendingStartRecord = false

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        if (pendingStartRecord) {
            pendingStartRecord = false
            actuallyStartRecord()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val ver = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) { "?" }
        supportActionBar?.title = "${getString(R.string.app_name)} $ver"

        pager = findViewById(R.id.pager)
        val tabs: TabLayout = findViewById(R.id.tabs)
        pager.adapter = PagerAdapter(this)
        TabLayoutMediator(tabs, pager) { tab, pos ->
            tab.text = if (pos == 0) getString(R.string.tab_live) else getString(R.string.tab_history)
        }.attach()

        if (intent?.getBooleanExtra("start_record", false) == true) {
            pager.post { startRecording() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("start_record", false)) {
            startRecording()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_REFRESH, 0, R.string.refresh).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(0, MENU_USAGE, 1, R.string.grant_usage)
        menu.add(0, MENU_INTERVAL, 2, R.string.interval)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            MENU_REFRESH -> {
                refreshAll()
                return true
            }
            MENU_USAGE -> {
                openUsageAccess()
                return true
            }
            MENU_INTERVAL -> {
                pickInterval()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    fun toggleRecordingFromUi() {
        if (Prefs.isRecording(this)) {
            RecordService.stop(this)
            Toast.makeText(this, "已停止记录", Toast.LENGTH_SHORT).show()
            refreshAll()
        } else {
            startRecording()
        }
    }

    private fun startRecording() {
        if (!TrafficProbe.hasUsageAccess(this)) {
            AlertDialog.Builder(this)
                .setTitle("需要用量访问权限")
                .setMessage("记录依赖系统 NetworkStats。请先打开「使用情况访问权限」里的「网络占用」。")
                .setPositiveButton("去授权") { _, _ -> openUsageAccess() }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        if (Build.VERSION.SDK_INT >= 33) {
            val ok = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!ok) {
                pendingStartRecord = true
                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        actuallyStartRecord()
    }

    private fun actuallyStartRecord() {
        RecordService.start(this)
        Toast.makeText(this, "已开始记录，默认每 ${Prefs.intervalSec(this)} 秒", Toast.LENGTH_SHORT).show()
        pager.postDelayed({ pager.currentItem = 1 }, 300)
        refreshAll()
    }

    private fun openUsageAccess() {
        try {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            Toast.makeText(this, "请打开「网络占用」开关", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            Toast.makeText(this, "无法打开设置", Toast.LENGTH_SHORT).show()
        }
    }

    private fun pickInterval() {
        val options = arrayOf("15 秒", "30 秒", "60 秒", "120 秒", "300 秒")
        val values = intArrayOf(15, 30, 60, 120, 300)
        val cur = Prefs.intervalSec(this)
        val checked = values.indexOfFirst { it == cur }.let { if (it < 0) 1 else it }
        AlertDialog.Builder(this)
            .setTitle("采样间隔")
            .setSingleChoiceItems(options, checked) { d, which ->
                Prefs.setIntervalSec(this, values[which])
                if (Prefs.isRecording(this)) {
                    RecordService.stop(this)
                    RecordService.start(this)
                }
                Toast.makeText(this, "已设为 ${values[which]} 秒", Toast.LENGTH_SHORT).show()
                d.dismiss()
                refreshAll()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun refreshAll() {
        for (f in supportFragmentManager.fragments) {
            if (f is LiveFragment) f.refresh()
            if (f is HistoryFragment) f.reload()
        }
    }

    private class PagerAdapter(fa: FragmentActivity) : FragmentStateAdapter(fa) {
        override fun getItemCount() = 2
        override fun createFragment(position: Int): Fragment =
            if (position == 0) LiveFragment() else HistoryFragment()
    }

    companion object {
        private const val MENU_REFRESH = 1
        private const val MENU_USAGE = 2
        private const val MENU_INTERVAL = 3
    }
}
