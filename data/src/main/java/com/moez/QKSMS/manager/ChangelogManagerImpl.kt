/*
 * Copyright (C) 2017 Moez Bhatti <moez.bhatti@gmail.com>
 *
 * This file is part of QKSMS.
 *
 * QKSMS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * QKSMS is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QKSMS.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.moez.QKSMS.manager

import android.content.Context
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.moez.QKSMS.util.Preferences
import io.reactivex.Single
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject

class ChangelogManagerImpl @Inject constructor(
    private val context: Context,
    private val prefs: Preferences
) : ChangelogManager {

    private val oldVersion: Int get() = prefs.version.get()
    private val versionCode: Int get() = context.packageManager.getPackageInfo(context.packageName, 0).versionCode

    override fun didUpdate(): Boolean {
        if (oldVersion == 0) {
            prefs.version.set(versionCode)
        }

        return when {
            oldVersion != versionCode -> true
            else -> false
        }
    }

    override fun getChangelog(): Single<ChangelogManager.Changelog> {
        val url = "https://firestore.googleapis.com/v1/projects/qksms-app/databases/(default)/documents/changelog"
        val httpUrl = HttpUrl.parse(url)
        val request = Request.Builder().url(httpUrl).build()
        val call = OkHttpClient().newCall(request)
        return Single
                .create<Response> { emitter ->
                    emitter.setCancellable { call.cancel() }
                    call.enqueue(object : Callback {
                        override fun onResponse(call: Call, response: Response) = emitter.onSuccess(response)
                        override fun onFailure(call: Call, e: IOException) = emitter.onError(e)
                    })
                }
                .map { response -> Gson().fromJson(response.body()?.string(), ChangelogResponse::class.java) }
                .map { response ->
                    response.documents
                            .sortedBy { document -> document.fields.versionCode.value }
                            .filter { document -> document.fields.versionCode.value.toInt() in (oldVersion + 1)..versionCode }
                }
                .map { documents ->
                    val added = documents.fold(listOf<String>()) { acc, document ->
                        acc + document.fields.added?.value?.values?.map { value -> value.value }.orEmpty()
                    }
                    val improved = documents.fold(listOf<String>()) { acc, document ->
                        acc + document.fields.improved?.value?.values?.map { value -> value.value }.orEmpty()
                    }
                    val fixed = documents.fold(listOf<String>()) { acc, document ->
                        acc + document.fields.fixed?.value?.values?.map { value -> value.value }.orEmpty()
                    }
                    ChangelogManager.Changelog(added, improved, fixed)
                }
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
    }

    override fun markChangelogSeen() {
        prefs.version.set(versionCode)
    }

    private data class ChangelogResponse(
        @SerializedName("documents") val documents: List<Document>
    )

    private data class Document(
        @SerializedName("fields") val fields: Changelog
    )

    private data class Changelog(
        @SerializedName("added") val added: ArrayField?,
        @SerializedName("improved") val improved: ArrayField?,
        @SerializedName("fixed") val fixed: ArrayField?,
        @SerializedName("versionName") val versionName: StringField,
        @SerializedName("versionCode") val versionCode: IntegerField
    )

    private data class ArrayField(
        @SerializedName("arrayValue") val value: ArrayValues
    )

    private data class ArrayValues(
        @SerializedName("values") val values: List<StringField>
    )

    private data class StringField(
        @SerializedName("stringValue") val value: String
    )

    private data class IntegerField(
        @SerializedName("integerValue") val value: String
    )

}
