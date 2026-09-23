package com.trigger.app.chats.repo.contacts.local

import androidx.room.TypeConverter
import com.trigger.app.core.domain.User
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

class RoomTypeConverters {

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val userAdapter = moshi.adapter(User::class.java)

    @TypeConverter
    fun userToJson(user: User?) = userAdapter.toJson(user)

    @TypeConverter
    fun jsonToUser(json: String) = userAdapter.fromJson(json)


}