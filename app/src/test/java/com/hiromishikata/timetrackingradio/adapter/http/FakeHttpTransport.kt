package com.hiromishikata.timetrackingradio.adapter.http

class FakeHttpTransport(private val scripted: ArrayDeque<() -> HttpResponse>) : HttpTransport {
    val requests = mutableListOf<HttpRequest>()

    override fun send(request: HttpRequest): HttpResponse {
        requests += request
        return scripted.removeFirst()()
    }
}
