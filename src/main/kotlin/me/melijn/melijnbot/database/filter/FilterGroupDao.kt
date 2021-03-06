package me.melijn.melijnbot.database.filter

import me.melijn.melijnbot.database.CacheDBDao
import me.melijn.melijnbot.database.DriverManager
import me.melijn.melijnbot.enums.FilterMode
import me.melijn.melijnbot.internals.utils.splitIETEL
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class FilterGroupDao(driverManager: DriverManager) : CacheDBDao(driverManager) {

    override val table: String = "filterGroups"
    override val tableStructure: String =
        "guildId bigint, filterGroupName varchar(32), punishGroupNames varchar(1024), channelIds varchar(2048), mode varchar(64), state boolean, points int, deleteHit boolean"
    override val primaryKey: String = "guildId, filterGroupName"

    override val cacheName: String = "filter:group"

    init {
        driverManager.registerTable(table, tableStructure, primaryKey)
    }

    fun add(guildId: Long, group: FilterGroup) {
        group.apply {
            val query =
                "INSERT INTO $table (guildId, filterGroupName, punishGroupNames, channelIds, mode, state, points, deleteHit) VALUES (?, ?, ?, ?, ?, ?, ?, ?) " +
                    "ON CONFLICT ($primaryKey) DO UPDATE SET punishGroupNames = ?, channelIds = ?, mode = ?, state = ?, points = ?, deleteHit = ?"
            driverManager.executeUpdate(
                query,
                // Insert args
                guildId,
                filterGroupName,
                punishGroupNames.joinToString(","),
                group.channels.joinToString(","),
                mode.toString(), state, points, deleteHit,
                // Update Set args
                punishGroupNames.joinToString(","),
                group.channels.joinToString(","),
                mode.toString(), state, points, deleteHit
            )
        }
    }

    suspend fun get(guildId: Long): List<FilterGroup> = suspendCoroutine {
        driverManager.executeQuery("SELECT * FROM $table WHERE guildId = ?", { rs ->
            val list = mutableListOf<FilterGroup>()
            while (rs.next()) {
                val channels = rs.getString("channelIds")

                list.add(
                    FilterGroup(
                        rs.getString("filterGroupName"),
                        rs.getString("punishGroupNames").splitIETEL(","),
                        rs.getBoolean("state"),
                        channels.splitIETEL(",")
                            .map { id ->
                                id.toLong()
                            }
                            .toLongArray(),
                        FilterMode.valueOf(rs.getString("mode")),
                        rs.getInt("points"),
                        rs.getBoolean("deleteHit")
                    )
                )
            }
            it.resume(list)
        }, guildId)
    }

    fun remove(guildId: Long, group: FilterGroup) {
        driverManager.executeUpdate(
            "DELETE FROM $table WHERE guildId = ? AND filterGroupName = ?",
            guildId, group.filterGroupName
        )
    }
}

data class FilterGroup(
    var filterGroupName: String,
    var punishGroupNames: List<String>,
    var state: Boolean,
    var channels: LongArray,
    var mode: FilterMode,
    var points: Int,
    var deleteHit: Boolean
)