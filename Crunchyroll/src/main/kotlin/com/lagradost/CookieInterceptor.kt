package com.lagradost

import com.lagradost.nicehttp.Requests
import okhttp3.*
import okhttp3.internal.parseCookie

/**
 * An HTTP session manager.
 *
 * This class simply keeps cookies across requests. No security about which site should use which cookies.
 *
 */

class CustomSession(
    client: OkHttpClient
) : Requests() {
    var cookies = mutableMapOf<String, Cookie>()

    init {
        this.baseClient = client
            .newBuilder()
            .addInterceptor {
                val time = System.currentTimeMillis()
                val request = it.request()
                request.headers.forEach { header ->
                    if (header.first.equals("cookie", ignoreCase = true)) {
                        val cookie = parseCookie(time, request.url, header.second) ?: return@forEach
                        cookies += cookie.name to cookie
                    }
                }
                it.proceed(request)
            }
            .cookieJar(CustomCookieJar())
            .build()
    }

    inner class CustomCookieJar : CookieJar {
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            return this@CustomSession.cookies.values.toList()
        }

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            this@CustomSession.cookies += cookies.map { it.name to it }
        }
    }
}