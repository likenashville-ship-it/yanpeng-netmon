package com.yanpeng.netmon

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
import com.yanpeng.netmon.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HistoryFragment : Fragment() {

    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var meta: TextView
    private lateinit var empty: TextView
    private lateinit var adapter: HistoryAdapter
    private var filterPkg: String? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_history, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        swipe = view.findViewById(R.id.swipeHistory)
        meta = view.findViewById(R.id.historyMeta)
        empty = view.findViewById(R.id.historyEmpty)
        val recycler: RecyclerView = view.findViewById(R.id.historyRecycler)
        adapter = HistoryAdapter()
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        view.findViewById<MaterialButton>(R.id.btnClear).setOnClickListener { confirmClear() }
        view.findViewById<MaterialButton>(R.id.btnFilter).setOnClickListener { pickFilter() }
        swipe.setOnRefreshListener { reload() }
        reload()
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    fun reload() {
        if (!isAdded) return
        swipe.isRefreshing = true
        viewLifecycleOwner.lifecycleScope.launch {
            val dao = AppDatabase.get(requireContext()).sampleDao()
            val since = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
            val rows = withContext(Dispatchers.IO) {
                val pkg = filterPkg
                if (pkg.isNullOrBlank()) dao.aggregateSince(since)
                else dao.aggregateForPackage(since, pkg)
            }
            val count = withContext(Dispatchers.IO) { dao.count() }
            if (!isAdded) return@launch
            adapter.submit(rows)
            empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
            val filterLabel = filterPkg?.let { " · 筛选 $it" } ?: ""
            meta.text = "近 7 天聚合 · 样本行 $count · 应用 ${rows.size}$filterLabel"
            swipe.isRefreshing = false
        }
    }

    private fun confirmClear() {
        AlertDialog.Builder(requireContext())
            .setTitle("清空记录")
            .setMessage("删除本地全部采样记录？不可恢复。")
            .setPositiveButton("清空") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        AppDatabase.get(requireContext()).sampleDao().clearAll()
                    }
                    Toast.makeText(requireContext(), "已清空", Toast.LENGTH_SHORT).show()
                    filterPkg = null
                    reload()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun pickFilter() {
        viewLifecycleOwner.lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) {
                AppDatabase.get(requireContext()).sampleDao().distinctApps()
            }
            if (!isAdded) return@launch
            val labels = mutableListOf("全部应用")
            labels.addAll(apps.map { "${it.appName} (${it.packageName})" })
            AlertDialog.Builder(requireContext())
                .setTitle("按应用筛选")
                .setItems(labels.toTypedArray()) { _, which ->
                    filterPkg = if (which == 0) null else apps[which - 1].packageName
                    reload()
                }
                .show()
        }
    }
}
