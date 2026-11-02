package com.yanpeng.netmon.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface SampleDao {
    @Insert
    suspend fun insertAll(items: List<SampleEntity>)

    @Query("DELETE FROM samples WHERE ts < :before")
    suspend fun deleteOlderThan(before: Long): Int

    @Query("DELETE FROM samples")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM samples")
    suspend fun count(): Int

    @Query(
        """
        SELECT packageName AS packageName, appName AS appName, uid AS uid,
               COUNT(*) AS hitCount,
               SUM(tcpCount) AS totalTcp,
               SUM(byteDelta) AS totalBytes,
               SUM(rxDelta) AS totalRx,
               SUM(txDelta) AS totalTx,
               MAX(ts) AS lastTs
        FROM samples
        WHERE ts >= :since
        GROUP BY packageName
        ORDER BY totalBytes DESC, hitCount DESC
        """
    )
    suspend fun aggregateSince(since: Long): List<AppAgg>

    @Query(
        """
        SELECT packageName AS packageName, appName AS appName, uid AS uid,
               COUNT(*) AS hitCount,
               SUM(tcpCount) AS totalTcp,
               SUM(byteDelta) AS totalBytes,
               SUM(rxDelta) AS totalRx,
               SUM(txDelta) AS totalTx,
               MAX(ts) AS lastTs
        FROM samples
        WHERE ts >= :since AND packageName = :pkg
        GROUP BY packageName
        ORDER BY lastTs DESC
        """
    )
    suspend fun aggregateForPackage(since: Long, pkg: String): List<AppAgg>

    @Query("SELECT DISTINCT packageName AS packageName, appName AS appName FROM samples ORDER BY appName COLLATE NOCASE")
    suspend fun distinctApps(): List<PkgName>
}

data class PkgName(val packageName: String, val appName: String)
