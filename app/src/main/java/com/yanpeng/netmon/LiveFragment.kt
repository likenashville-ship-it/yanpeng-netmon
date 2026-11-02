package com.yanpeng.netmon

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LiveFragment : Fragment() {

    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var summary: TextView
    private lateinit var summaryCompact: TextView
    private lateinit var summaryBody: LinearLayout
    private lateinit var summaryHeader: View
    private lateinit var btnToggleSummary: TextView
    private lateinit var recordStatus: TextView
    private lateinit var errorText: TextView
    private lateinit var emptyText: TextView
    private lateinit var listHeader: TextView
    private lateinit var btnGrant: MaterialButton
    private lateinit var btnRecord: MaterialButton
    private lateinit var adapter: AppConnAdapter
    private var lastWindowMs: Long = 60_000L
    private var lastCompact: String = ""

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_live, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        swipe = view.findViewById(R.id.swipeRefresh)
        summary = view.findViewById(R.id.networkSummary)
        summaryCompact = view.findViewById(R.id.summaryCompact)
        summaryBody = view.findViewById(R.id.summaryBody)
        summaryHeader = view.findViewById(R.id.summaryHeader)
        btnToggleSummary = view.findViewById(R.id.btnToggleSummary)
        recordStatus = view.findViewById(R.id.recordStatus)
        errorText = view.findViewById(R.id.errorText)
        emptyText = view.findViewById(R.id.emptyText)
        listHeader = view.findViewById(R.id.listHeader)
        btnGrant = view.findViewById(R.id.btnGrant)
        btnRecord = view.findViewById(R.id.btnRecord)
        val recycler: RecyclerView = view.findViewById(R.id.recycler)
        adapter = AppConnAdapter { lastWindowMs }
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        btnGrant.setOnClickListener { openUsageAccess() }
        btnRecord.setOnClickListener { toggleRecord() }
        val toggle = View.OnClickListener { toggleSummary() }
        btnToggleSummary.setOnClickListener(toggle)
        summaryHeader.setOnClickListener(toggle)
        applySummaryExpanded(Prefs.summaryExpanded(requireContext()), animate = false)

        swipe.setOnRefreshListener { refresh() }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun toggleSummary() {
        val next = !Prefs.summaryExpanded(requireContext())
        Prefs.setSummaryExpanded(requireContext(), next)
        applySummaryExpanded(next, animate = true)
    }

    private fun applySummaryExpanded(expanded: Boolean, animate: Boolean) {
        if (expanded) {
            summaryBody.visibility = View.VISIBLE
            btnToggleSummary.text = "收起"
        } else {
            summaryBody.visibility = View.GONE
            btnToggleSummary.text = "展开"
        }
        if (animate) {
            summaryBody.alpha = 0f
            if (expanded) {
                summaryBody.animate().alpha(1f).setDuration(150).start()
            }
        }
    }

    private fun openUsageAccess() {
        try {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            Toast.makeText(
                requireContext(),
                "请找到「网络占用」并打开开关",
                Toast.LENGTH_LONG
            ).show()
        } catch (_: Exception) {
            Toast.makeText(requireContext(), "无法打开设置页", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleRecord() {
        val act = activity as? MainActivity ?: return
        act.toggleRecordingFromUi()
        updateButtons()
    }

    fun refresh() {
        if (!isAdded) return
        swipe.isRefreshing = true
        updateButtons()
        viewLifecycleOwner.lifecycleScope.launch {
            val ctx = requireContext().applicationContext
            val summaryBase = withContext(Dispatchers.IO) { NetworkSummary.build(ctx) }
            val hasUsage = withContext(Dispatchers.IO) { TrafficProbe.hasUsageAccess(ctx) }
            val interval = Prefs.intervalSec(ctx)
            val windowMs = (interval * 1000L * 2).coerceIn(15_000L, 120_000L)
            lastWindowMs = windowMs
            val vpn = withContext(Dispatchers.IO) { ProxyDetect.vpnStatus(ctx) }
            val probe = withContext(Dispatchers.IO) { NetProbe.scan(ctx) }
            val rows = if (hasUsage) {
                withContext(Dispatchers.IO) { TrafficProbe.recent(ctx, windowMs) }
            } else emptyList()
            val insights = withContext(Dispatchers.IO) {
                InsightBuilder.build(ctx, rows, windowMs, probe, vpn)
            }

            if (!isAdded) return@launch
            val total = insights.sumOf { it.total }
            val overview = InsightBuilder.overviewLine(insights, vpn)
            val proxy = insights.count { it.route == RouteMode.PROXY }
            val direct = insights.count { it.route == RouteMode.DIRECT }
            lastCompact = buildString {
                append(NetworkSummary.currentType(ctx))
                if (vpn.vpnActive) append(" · Clash开")
                append(" · ${insights.size}应用")
                append(" · ${Bytes.fmt(total)}/${windowMs / 1000}s")
                if (vpn.proxiedUidRanges.isEmpty() && vpn.vpnActive) {
                    append(" · 名单未同步")
                } else {
                    append(" · 代理$proxy/直连$direct")
                }
            }
            summaryCompact.text = lastCompact
            summary.text = summaryBase + "\n" + overview +
                "\n最近约 ${windowMs / 1000}s 流量合计：${Bytes.fmt(total)}" +
                if (hasUsage) "" else "\n（未授权用量访问）" +
                if (!probe.readable) "\n连接详情：不可用（${probe.error ?: "系统禁止读套接字"}）。路由改看 VPN UID 列表。"
                else ""

            if (!hasUsage) {
                errorText.visibility = View.VISIBLE
                errorText.text = getString(R.string.need_usage)
                adapter.submit(emptyList())
                emptyText.visibility = View.GONE
                listHeader.text = "最近间隔流量（不可用）"
            } else {
                errorText.visibility = View.GONE
                adapter.submit(insights)
                emptyText.visibility = if (insights.isEmpty()) View.VISIBLE else View.GONE
                listHeader.text = "点进应用看连接 / 是否走 Clash（${insights.size} 个）"
            }
            // Keep collapsed preference; don't force-open on refresh
            applySummaryExpanded(Prefs.summaryExpanded(requireContext()), animate = false)
            swipe.isRefreshing = false
        }
    }

    private fun updateButtons() {
        if (!isAdded) return
        val ctx = requireContext()
        val recording = Prefs.isRecording(ctx)
        recordStatus.text = if (recording) {
            "● 记录中 · 每 ${Prefs.intervalSec(ctx)}s"
        } else {
            "○ 未记录"
        }
        btnRecord.text = if (recording) getString(R.string.stop_record) else getString(R.string.start_record)
        val granted = TrafficProbe.hasUsageAccess(ctx)
        btnGrant.text = if (granted) "已授权用量访问" else getString(R.string.grant_usage)
        btnGrant.isEnabled = !granted
    }
}
