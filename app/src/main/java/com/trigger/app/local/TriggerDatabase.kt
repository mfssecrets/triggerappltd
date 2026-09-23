package com.trigger.app.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class MapTypeConverter {
    @TypeConverter
    fun mapToString(map: Map<String, Any>?): String {
        if (map == null) return "{}"
        return org.json.JSONObject(map).toString()
    }

    @TypeConverter
    fun stringToMap(value: String?): Map<String, Any> {
        if (value.isNullOrEmpty()) return emptyMap()
        val json = org.json.JSONObject(value)
        val map = mutableMapOf<String, Any>()
        json.keys().forEach { key ->
            map[key] = json.get(key) ?: ""
        }
        return map
    }
}

@Database(
    entities = [MessageEntity::class, ChatEntity::class, UserEntity::class, MediaFileEntity::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(MapTypeConverter::class)
abstract class TriggerDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun chatDao(): ChatDao
    abstract fun userDao(): UserDao
    abstract fun mediaFileDao(): MediaFileDao

    companion object {
        const val DATABASE_NAME = "trigger_app.db"
    }
}
