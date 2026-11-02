package com.yanpeng.netmon.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "samples",
    indices = [Index("ts"), Index("packageName"), Index("uid")]
)
data class SampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    val uid: Int,
    val packageName: String,
    val appName: String,
    val tcpCount: Int = 0,
    val rxDelta: Long = 0,
    val txDelta: Long = 0,
    val byteDelta: Long = 0, // rx+tx, for sorting convenience
    val networkType: String,
    val source: String = "traffic"
)

data class AppAgg(
    val packageName: String,
    val appName: String,
    val uid: Int,
    val hitCount: Int,
    val totalTcp: Int,
    val totalBytes: Long,
    val totalRx: Long,
    val totalTx: Long,
    val lastTs: Long
)
